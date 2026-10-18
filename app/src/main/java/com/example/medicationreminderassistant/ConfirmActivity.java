package com.example.medicationreminderassistant;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.medicationreminderassistant.model.Medication;
import com.example.medicationreminderassistant.model.RecognizeResult;
import com.example.medicationreminderassistant.service.MedicationDatabase;
import com.example.medicationreminderassistant.service.NotificationService;
import com.example.medicationreminderassistant.service.RecognitionService;
import com.example.medicationreminderassistant.util.ImageUtils;
import com.example.medicationreminderassistant.util.InsetsHelper;
import com.example.medicationreminderassistant.util.TimeUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 识别结果确认页：展示 OCR 解析出的候选药品，允许逐条修改、删除、手工新增，
 * 核对后写入数据库并为每条药品注册每日定时提醒。
 */
public class ConfirmActivity extends AppCompatActivity {

    public static final String EXTRA_PHOTO_PATH = "photo_path";
    private static final String TAG = "ConfirmActivity";

    private ImageView ivPrescription;
    private ProgressBar progressBar;
    private TextView tvStatus;
    private LinearLayout medicationContainer;
    private View btnSave;

    private RecognitionService recognitionService;
    private MedicationDatabase database;
    private String photoPath;
    private String persistedPhotoPath;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_confirm);
        InsetsHelper.applyBottomNavInset(this);
        setTitle("核对识别结果");

        ivPrescription = findViewById(R.id.ivPrescription);
        progressBar = findViewById(R.id.progressBar);
        tvStatus = findViewById(R.id.tvStatus);
        medicationContainer = findViewById(R.id.medicationContainer);
        btnSave = findViewById(R.id.btnSave);

        findViewById(R.id.btnAdd).setOnClickListener(v -> addMedicationCard(null));
        btnSave.setOnClickListener(v -> saveAll());

        database = MedicationDatabase.getInstance(this);
        photoPath = getIntent().getStringExtra(EXTRA_PHOTO_PATH);

        if (photoPath == null || !new File(photoPath).exists()) {
            showStatus("未找到处方照片，可手工添加药品", false);
            addMedicationCard(null);
            return;
        }

        recognitionService = new RecognitionService();
        recognitionService.recognize(this, photoPath, new RecognitionService.RecognizeCallback() {
            @Override
            public void onSuccess(RecognizeResult result) {
                runOnUiThread(() -> {
                    showPreview();
                    List<Medication> list = result.getMedications();
                    if (list.isEmpty()) {
                        showStatus("未能自动识别药品，请对照处方手工填写", false);
                        addMedicationCard(null);
                    } else {
                        showStatus("识别到 " + list.size() + " 种药品，请核对后保存", false);
                        for (Medication m : list) {
                            addMedicationCard(m);
                        }
                    }
                });
            }

            @Override
            public void onFailure(Exception e) {
                Log.e(TAG, "识别失败", e);
                runOnUiThread(() -> {
                    showPreview();
                    showStatus("识别失败：" + e.getMessage() + "，可手工填写", false);
                    addMedicationCard(null);
                });
            }
        });
    }

    private void showPreview() {
        Bitmap bitmap = ImageUtils.decodeSampledBitmap(photoPath, 800, 400);
        if (bitmap != null) {
            ivPrescription.setImageBitmap(bitmap);
            ivPrescription.setVisibility(View.VISIBLE);
        }
    }

    private void showStatus(String text, boolean loading) {
        tvStatus.setText(text);
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    /** 添加一张可编辑的药品卡片；medication 为空时是空白卡片 */
    private void addMedicationCard(@Nullable Medication medication) {
        View card = getLayoutInflater().inflate(
                R.layout.item_medication_edit, medicationContainer, false);

        EditText etName = card.findViewById(R.id.etName);
        EditText etDosage = card.findViewById(R.id.etDosage);
        EditText etTimes = card.findViewById(R.id.etTimes);
        EditText etNote = card.findViewById(R.id.etNote);
        TextView tvIndex = card.findViewById(R.id.tvIndex);
        TextView btnDelete = card.findViewById(R.id.btnDelete);

        if (medication != null) {
            etName.setText(safe(medication.getName()));
            etDosage.setText(safe(medication.getDosage()));
            etTimes.setText(TimeUtils.timesToCsv(medication.getTimes()));
            etNote.setText(safe(medication.getNote()));
            // 若药名被标准库纠错，提示用户 OCR 原始识别内容以便核对
            String ocrName = medication.getOcrName();
            if (ocrName != null && !ocrName.isEmpty() && !ocrName.equals(medication.getName())) {
                TextView tvOcrHint = card.findViewById(R.id.tvOcrHint);
                tvOcrHint.setText("OCR 识别为：" + ocrName + "（已校准为上方名称）");
                tvOcrHint.setVisibility(View.VISIBLE);
            }
        }

        btnDelete.setOnClickListener(v -> medicationContainer.removeView(card));
        medicationContainer.addView(card);
        refreshIndexes();
    }

    private void refreshIndexes() {
        for (int i = 0; i < medicationContainer.getChildCount(); i++) {
            View card = medicationContainer.getChildAt(i);
            TextView tvIndex = card.findViewById(R.id.tvIndex);
            tvIndex.setText("药品 " + (i + 1));
        }
    }

    private void saveAll() {
        int count = medicationContainer.getChildCount();
        if (count == 0) {
            Toast.makeText(this, "请至少添加一种药品", Toast.LENGTH_SHORT).show();
            return;
        }

        List<Medication> toSave = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            View card = medicationContainer.getChildAt(i);
            EditText etName = card.findViewById(R.id.etName);
            EditText etDosage = card.findViewById(R.id.etDosage);
            EditText etTimes = card.findViewById(R.id.etTimes);
            EditText etNote = card.findViewById(R.id.etNote);

            String name = etName.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "第 " + (i + 1) + " 种药品名称为空，请填写或删除",
                        Toast.LENGTH_SHORT).show();
                etName.requestFocus();
                return;
            }

            ArrayList<String> times = TimeUtils.parseTimesInput(
                    etTimes.getText().toString());
            if (times.isEmpty()) {
                times = TimeUtils.defaultTimesForFrequency(1);
            }

            Medication m = new Medication();
            m.setName(name);
            m.setDosage(etDosage.getText().toString().trim());
            m.setTimes(times);
            m.setNote(etNote.getText().toString().trim());
            m.setStartDate(TimeUtils.today());
            m.setCreatedAt(System.currentTimeMillis());
            toSave.add(m);
        }

        btnSave.setEnabled(false);

        // 照片只持久化一次，所有药品共用同一张处方图
        if (photoPath != null) {
            persistedPhotoPath = ImageUtils.persistPrescriptionImage(this, photoPath);
        }

        int saved = 0;
        for (Medication m : toSave) {
            m.setPhotoPath(persistedPhotoPath);
            long id = database.insertMedication(m);
            if (id > 0) {
                m.setId(id);
                NotificationService.scheduleForMedication(getApplicationContext(), m);
                saved++;
            }
        }

        Toast.makeText(this, "已保存 " + saved + " 种药品并开启提醒", Toast.LENGTH_LONG).show();
        setResult(RESULT_OK);
        finish();
    }

    private String safe(String s) {
        return s != null ? s : "";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recognitionService != null) {
            recognitionService.close();
        }
    }
}
