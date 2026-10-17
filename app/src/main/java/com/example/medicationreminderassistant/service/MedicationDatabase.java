package com.example.medicationreminderassistant.service;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.example.medicationreminderassistant.model.Medication;
import com.example.medicationreminderassistant.util.TimeUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 药品与服药打卡记录的 SQLite 存储。
 * medications：药品基本信息与每日提醒时间点（CSV 存储）；
 * intake_log：每天每条药品的打卡记录（medication_id + log_date 唯一），
 * dose_count 记录当日实际服用次数，用于日历判断是否按次达标。
 */
public class MedicationDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "medication.db";
    private static final int DB_VERSION = 2;

    private static final String TABLE_MED = "medications";
    private static final String TABLE_LOG = "intake_log";

    private static volatile MedicationDatabase instance;

    public static MedicationDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (MedicationDatabase.class) {
                if (instance == null) {
                    instance = new MedicationDatabase(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private MedicationDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_MED + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "name TEXT NOT NULL, "
                + "dosage TEXT, "
                + "times TEXT, "
                + "note TEXT, "
                + "photo_path TEXT, "
                + "enabled INTEGER DEFAULT 1, "
                + "start_date TEXT, "
                + "created_at INTEGER)");

        db.execSQL("CREATE TABLE " + TABLE_LOG + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "medication_id INTEGER NOT NULL, "
                + "log_date TEXT NOT NULL, "
                + "taken_at INTEGER, "
                + "dose_count INTEGER NOT NULL DEFAULT 1, "
                + "UNIQUE(medication_id, log_date))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // v2：打卡记录增加“当日服用次数”，旧记录按已服 1 次迁移
            db.execSQL("ALTER TABLE " + TABLE_LOG
                    + " ADD COLUMN dose_count INTEGER NOT NULL DEFAULT 1");
        }
    }

    /** 新增药品，返回新记录 id */
    public long insertMedication(Medication medication) {
        SQLiteDatabase db = getWritableDatabase();
        return db.insert(TABLE_MED, null, toValues(medication));
    }

    public void updateMedication(Medication medication) {
        SQLiteDatabase db = getWritableDatabase();
        db.update(TABLE_MED, toValues(medication), "_id = ?",
                new String[]{String.valueOf(medication.getId())});
    }

    public void deleteMedication(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_MED, "_id = ?", new String[]{String.valueOf(id)});
        db.delete(TABLE_LOG, "medication_id = ?", new String[]{String.valueOf(id)});
    }

    public Medication getMedication(long id) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(TABLE_MED, null, "_id = ?",
                new String[]{String.valueOf(id)}, null, null, null)) {
            if (cursor.moveToFirst()) {
                return fromCursor(cursor);
            }
        }
        return null;
    }

    public List<Medication> getAllMedications() {
        List<Medication> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(TABLE_MED, null, null, null,
                null, null, "created_at DESC")) {
            while (cursor.moveToNext()) {
                list.add(fromCursor(cursor));
            }
        }
        return list;
    }

    public void setEnabled(long id, boolean enabled) {
        ContentValues values = new ContentValues();
        values.put("enabled", enabled ? 1 : 0);
        getWritableDatabase().update(TABLE_MED, values, "_id = ?",
                new String[]{String.valueOf(id)});
    }

    /**
     * 记录当日服用 1 次（重复点击累加次数，每日次数上限不在这里限制）。
     * 首次打卡插入记录，之后仅累加 dose_count。
     */
    public void recordDose(long medicationId, String date) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("INSERT INTO " + TABLE_LOG
                        + "(medication_id, log_date, taken_at, dose_count) VALUES (?,?,?,1) "
                        + "ON CONFLICT(medication_id, log_date) DO UPDATE SET "
                        + "taken_at = excluded.taken_at, "
                        + "dose_count = dose_count + 1",
                new Object[]{medicationId, date, System.currentTimeMillis()});
    }

    /** 当日实际服用次数（无记录为 0） */
    public int getDoseCount(long medicationId, String date) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(TABLE_LOG, new String[]{"dose_count"},
                "medication_id = ? AND log_date = ?",
                new String[]{String.valueOf(medicationId), date},
                null, null, null)) {
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        }
        return 0;
    }

    /** 查询一段时间内每日的服用次数，key=yyyy-MM-dd，value=次数 */
    public Map<String, Integer> getDoseCounts(long medicationId,
                                              String fromDate, String toDate) {
        Map<String, Integer> result = new HashMap<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(TABLE_LOG, new String[]{"log_date", "dose_count"},
                "medication_id = ? AND log_date BETWEEN ? AND ?",
                new String[]{String.valueOf(medicationId), fromDate, toDate},
                null, null, null)) {
            while (cursor.moveToNext()) {
                result.put(cursor.getString(0), cursor.getInt(1));
            }
        }
        return result;
    }

    public boolean isTaken(long medicationId, String date) {
        return getDoseCount(medicationId, date) > 0;
    }

    private ContentValues toValues(Medication m) {
        ContentValues values = new ContentValues();
        values.put("name", m.getName());
        values.put("dosage", m.getDosage());
        values.put("times", TimeUtils.timesToCsv(m.getTimes()));
        values.put("note", m.getNote());
        values.put("photo_path", m.getPhotoPath());
        values.put("enabled", m.isEnabled() ? 1 : 0);
        values.put("start_date", m.getStartDate());
        values.put("created_at", m.getCreatedAt() > 0 ? m.getCreatedAt() : System.currentTimeMillis());
        return values;
    }

    private Medication fromCursor(Cursor cursor) {
        Medication m = new Medication();
        m.setId(cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
        m.setName(cursor.getString(cursor.getColumnIndexOrThrow("name")));
        m.setDosage(cursor.getString(cursor.getColumnIndexOrThrow("dosage")));
        m.setTimes(TimeUtils.timesFromCsv(
                cursor.getString(cursor.getColumnIndexOrThrow("times"))));
        m.setNote(cursor.getString(cursor.getColumnIndexOrThrow("note")));
        m.setPhotoPath(cursor.getString(cursor.getColumnIndexOrThrow("photo_path")));
        m.setEnabled(cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) == 1);
        m.setStartDate(cursor.getString(cursor.getColumnIndexOrThrow("start_date")));
        m.setCreatedAt(cursor.getLong(cursor.getColumnIndexOrThrow("created_at")));
        return m;
    }
}
