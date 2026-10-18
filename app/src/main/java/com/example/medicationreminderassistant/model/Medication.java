package com.example.medicationreminderassistant.model;

import java.util.ArrayList;

/**
 * 用药提醒的核心数据模型：一条药品记录。
 * times 为每天需要服药的时间点，格式 "HH:mm"，例如 ["08:00", "20:00"]。
 */
public class Medication {

    private long id;
    private String name;
    private String dosage;
    private ArrayList<String> times;
    private String note;
    private String photoPath;
    private boolean enabled;
    private String startDate;
    private long createdAt;

    /** OCR 原始识别的药品名（仅用于确认页提示，不入库）；为空表示未被纠错 */
    private transient String ocrName;

    public Medication() {
        this.times = new ArrayList<>();
        this.enabled = true;
    }

    public Medication(String name, String dosage, ArrayList<String> times,
                      String note, String photoPath, String startDate, long createdAt) {
        this.name = name;
        this.dosage = dosage;
        this.times = times != null ? times : new ArrayList<>();
        this.note = note;
        this.photoPath = photoPath;
        this.enabled = true;
        this.startDate = startDate;
        this.createdAt = createdAt;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDosage() {
        return dosage;
    }

    public void setDosage(String dosage) {
        this.dosage = dosage;
    }

    public ArrayList<String> getTimes() {
        return times;
    }

    public void setTimes(ArrayList<String> times) {
        this.times = times;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getPhotoPath() {
        return photoPath;
    }

    public void setPhotoPath(String photoPath) {
        this.photoPath = photoPath;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getStartDate() {
        return startDate;
    }

    public void setStartDate(String startDate) {
        this.startDate = startDate;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public String getOcrName() {
        return ocrName;
    }

    public void setOcrName(String ocrName) {
        this.ocrName = ocrName;
    }
}
