package com.example.medicationreminderassistant.model;

import java.util.ArrayList;
import java.util.List;

/**
 * OCR 识别 + 处方解析的结果。
 * rawText 是 OCR 原始文本（识别失败/解析为空时可让用户对照手工录入），
 * medications 是按规则从文本中提取出的候选药品，可能为空，需要用户在确认页核对。
 */
public class RecognizeResult {

    private final String rawText;
    private final List<Medication> medications;

    public RecognizeResult(String rawText, List<Medication> medications) {
        this.rawText = rawText != null ? rawText : "";
        this.medications = medications != null ? medications : new ArrayList<>();
    }

    public String getRawText() {
        return rawText;
    }

    public List<Medication> getMedications() {
        return medications;
    }
}
