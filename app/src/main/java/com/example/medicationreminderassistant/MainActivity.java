package com.example.medicationreminderassistant;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View; // ★ 就是这个，现在帮你补上了！
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.medicationreminderassistant.service.RecognitionService;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_CAMERA = 2001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 绑定按钮并设置点击事件
        Button btnOpenCamera = findViewById(R.id.btnOpenCamera);
        btnOpenCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, CameraActivity.class);
                startActivityForResult(intent, REQUEST_CODE_CAMERA);
            }
        });
    }

    // 接收相机返回的结果
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_CAMERA && resultCode == RESULT_OK && data != null) {
            // 拿到图片路径
            String photoPath = data.getStringExtra(CameraActivity.EXTRA_PHOTO_PATH);

            // 打印到 Logcat 检查
            Log.d("MainActivity", "拍照成功！图片路径: " + photoPath);

            // 弹个提示
            Toast.makeText(this, "拍照成功，图片已保存！", Toast.LENGTH_SHORT).show();

            // TODO: 下一步，把 path 传给 OCR 识别服务
            if (requestCode == REQUEST_CODE_CAMERA && resultCode == RESULT_OK && data != null) {
                String path = data.getStringExtra(CameraActivity.EXTRA_PHOTO_PATH);
                Log.d("MainActivity", "拍照成功！图片路径: " + path);
                Toast.makeText(this, "正在识别处方单...", Toast.LENGTH_SHORT).show();

                // ★ 调用 OCR 识别
                com.example.medicationreminderassistant.service.RecognitionService recognitionService
                        = new com.example.medicationreminderassistant.service.RecognitionService();

                // 为了防止阻塞，通常需要放在子线程，但 ML Kit 内部自带异步，可以直接调用
                recognitionService.recognizeText(this, photoPath, new RecognitionService.OcrCallback() {
                    @Override
                    public void onSuccess(String text) {
                        // 识别成功！把结果打印出来
                        Log.d("OCR_RESULT", "====== 识别结果 ======");
                        Log.d("OCR_RESULT", text);
                        Log.d("OCR_RESULT", "======================");

                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "识别成功，请看 Logcat", Toast.LENGTH_LONG).show();
                        });
                    }

                    @Override
                    public void onFailure(Exception e) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "识别失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        });
                    }
                });
            }
        }
    }
}