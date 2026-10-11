package com.example.medicationreminderassistant.service; // ⚠️ 改成你的包名.service

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.File;
import java.io.IOException;

public class RecognitionService {

    private static final String TAG = "RecognitionService";
    private final TextRecognizer recognizer;

    // 定义回调接口，把结果传出去
    public interface OcrCallback {
        void onSuccess(String text);
        void onFailure(Exception e);
    }

    public RecognitionService() {
        // 使用中文识别器
        recognizer = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build()
        );
    }

    /**
     * 识别图片中的文字
     * @param context 上下文
     * @param imagePath 图片的本地路径
     * @param callback 结果回调
     */
    public void recognizeText(Context context, String imagePath, OcrCallback callback) {
        try {
            // 1. 把本地图片路径转成 ML Kit 能识别的 InputImage
            File file = new File(imagePath);
            Uri uri = Uri.fromFile(file);
            InputImage image = InputImage.fromFilePath(context, uri);

            // 2. 开始识别
            recognizer.process(image)
                    .addOnSuccessListener(visionText -> {
                        // 识别成功，提取完整文本
                        String resultText = visionText.getText();
                        Log.d(TAG, "OCR 识别成功，文字长度: " + resultText.length());
                        callback.onSuccess(resultText);
                    })
                    .addOnFailureListener(e -> {
                        // 识别失败
                        Log.e(TAG, "OCR 识别失败", e);
                        callback.onFailure(e);
                    });

        } catch (IOException e) {
            Log.e(TAG, "读取图片失败", e);
            callback.onFailure(e);
        }
    }

    /** 释放资源 */
    public void close() {
        if (recognizer != null) {
            recognizer.close();
        }
    }
}