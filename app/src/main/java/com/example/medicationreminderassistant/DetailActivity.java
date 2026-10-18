package com.example.medicationreminderassistant;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.example.medicationreminderassistant.model.Medication;
import com.example.medicationreminderassistant.service.MedicationDatabase;
import com.example.medicationreminderassistant.service.NotificationService;
import com.example.medicationreminderassistant.util.ImageUtils;
import com.example.medicationreminderassistant.util.InsetsHelper;
import com.example.medicationreminderassistant.util.TimeUtils;
import com.example.medicationreminderassistant.widget.MedicationCalendarView;

import java.io.File;
import java.util.ArrayList;

/**
 * 药品详情页：查看信息、查看处方照片、开关提醒、今日服药打卡、删除药品。
 */
public class DetailActivity extends AppCompatActivity {

    public static final String EXTRA_MEDICATION_ID = "medication_id";

    private MedicationDatabase database;
    private Medication medication;

    private TextView tvName;
    private TextView tvDosage;
    private TextView tvTimes;
    private TextView tvNote;
    private TextView tvStartDate;
    private TextView tvAvatar;
    private SwitchCompat switchEnabled;
    private Button btnTaken;
    private View cardPhoto;
    private MedicationCalendarView calendarView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        // 底部操作按钮自动避开导航栏
        InsetsHelper.applyBottomNavInset(this);

        long medicationId = getIntent().getLongExtra(EXTRA_MEDICATION_ID, -1L);
        database = MedicationDatabase.getInstance(this);
        medication = database.getMedication(medicationId);
        if (medication == null) {
            Toast.makeText(this, "药品不存在或已删除", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        bindViews();
        showMedication();
    }

    private void bindViews() {
        tvName = findViewById(R.id.tvName);
        tvAvatar = findViewById(R.id.tvAvatar);
        tvDosage = findViewById(R.id.tvDosage);
        tvTimes = findViewById(R.id.tvTimes);
        tvNote = findViewById(R.id.tvNote);
        tvStartDate = findViewById(R.id.tvStartDate);
        switchEnabled = findViewById(R.id.switchEnabled);
        btnTaken = findViewById(R.id.btnTaken);
        cardPhoto = findViewById(R.id.cardPhoto);
        calendarView = findViewById(R.id.calendarView);

        btnTaken.setOnClickListener(v -> {
            database.recordDose(medication.getId(), TimeUtils.today());
            // 已服用：取消尚未触发的漏服重提醒
            NotificationService.cancelReReminder(getApplicationContext(), medication.getId());
            refreshTakenState();
            calendarView.refresh();
            int remaining = requiredDoses()
                    - database.getDoseCount(medication.getId(), TimeUtils.today());
            if (remaining <= 0) {
                Toast.makeText(this, "今日已按次服用完成", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "已记录 1 次服药", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btnEdit).setOnClickListener(v -> showEditDialog());
        findViewById(R.id.btnDelete).setOnClickListener(v -> confirmDelete());
    }

    private void showMedication() {
        tvName.setText(medication.getName());
        String name = medication.getName();
        tvAvatar.setText(name != null && !name.isEmpty() ? name.substring(0, 1) : "药");
        tvDosage.setText(emptyAsDash(medication.getDosage()));
        tvTimes.setText(TimeUtils.formatTimes(medication.getTimes()));
        tvNote.setText(emptyAsDash(medication.getNote()));
        tvStartDate.setText(emptyAsDash(medication.getStartDate()));

        // 先设置状态再挂监听，避免初始化触发回调
        switchEnabled.setChecked(medication.isEnabled());
        switchEnabled.setOnCheckedChangeListener((button, checked) -> {
            medication.setEnabled(checked);
            database.setEnabled(medication.getId(), checked);
            if (checked) {
                NotificationService.scheduleForMedication(getApplicationContext(), medication);
                Toast.makeText(this, "已开启提醒", Toast.LENGTH_SHORT).show();
            } else {
                NotificationService.cancelForMedication(getApplicationContext(), medication.getId());
                Toast.makeText(this, "已关闭提醒", Toast.LENGTH_SHORT).show();
            }
        });

        showPhoto();
        calendarView.setMedication(medication);
        refreshTakenState();
    }

    private int requiredDoses() {
        return Math.max(1, medication.getTimes() == null ? 1 : medication.getTimes().size());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从全屏响铃页打卡返回后同步日历与按钮状态
        if (medication != null) {
            refreshTakenState();
            if (calendarView != null) {
                calendarView.refresh();
            }
        }
    }

    private void showPhoto() {
        String path = medication.getPhotoPath();
        if (path == null || path.isEmpty() || !new File(path).exists()) {
            cardPhoto.setVisibility(View.GONE);
            return;
        }
        Bitmap bitmap = ImageUtils.decodeSampledBitmap(path, 800, 600);
        if (bitmap != null) {
            ((ImageView) findViewById(R.id.ivPhoto)).setImageBitmap(bitmap);
        } else {
            cardPhoto.setVisibility(View.GONE);
        }
    }

    private void refreshTakenState() {
        int taken = database.getDoseCount(medication.getId(), TimeUtils.today());
        int required = requiredDoses();
        if (taken >= required) {
            btnTaken.setText("今日已按次服用 ✓（" + required + "/" + required + "）");
            btnTaken.setEnabled(false);
            btnTaken.setAlpha(0.6f);
        } else if (taken > 0) {
            btnTaken.setText("记录一次服药（今日 " + taken + "/" + required + " 次）");
            btnTaken.setEnabled(true);
            btnTaken.setAlpha(1f);
        } else {
            btnTaken.setText("确认今日已服用（每日 " + required + " 次）");
            btnTaken.setEnabled(true);
            btnTaken.setAlpha(1f);
        }
    }

    /** 编辑药品信息：修改后保存数据库并重排提醒 */
    private void showEditDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_edit_medication, null);
        EditText etName = view.findViewById(R.id.etEditName);
        EditText etDosage = view.findViewById(R.id.etEditDosage);
        EditText etTimes = view.findViewById(R.id.etEditTimes);
        EditText etNote = view.findViewById(R.id.etEditNote);

        etName.setText(safeText(medication.getName()));
        etDosage.setText(safeText(medication.getDosage()));
        etTimes.setText(TimeUtils.timesToCsv(medication.getTimes()));
        etNote.setText(safeText(medication.getNote()));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("编辑药品信息")
                .setView(view)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.show();

        // 自己接管保存按钮，校验失败时不关闭对话框
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "药品名称不能为空", Toast.LENGTH_SHORT).show();
                return;
            }

            medication.setName(name);
            medication.setDosage(etDosage.getText().toString().trim());
            medication.setNote(etNote.getText().toString().trim());

            ArrayList<String> times = TimeUtils.parseTimesInput(etTimes.getText().toString());
            if (!times.isEmpty()) {
                medication.setTimes(times);
            }

            database.updateMedication(medication);

            // 时间或开关状态可能变化：取消旧提醒后按新信息重排
            NotificationService.cancelForMedication(getApplicationContext(), medication.getId());
            if (medication.isEnabled()) {
                NotificationService.scheduleForMedication(getApplicationContext(), medication);
            }

            showMedication();
            Toast.makeText(this, "已保存修改", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
    }

    private String safeText(String value) {
        return value != null ? value : "";
    }

    private String emptyAsDash(String value) {
        return value == null || value.isEmpty() ? "—" : value;
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("删除药品")
                .setMessage("确定删除「" + medication.getName() + "」及其全部提醒吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    NotificationService.cancelForMedication(
                            getApplicationContext(), medication.getId());
                    database.deleteMedication(medication.getId());
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .show();
    }
}
