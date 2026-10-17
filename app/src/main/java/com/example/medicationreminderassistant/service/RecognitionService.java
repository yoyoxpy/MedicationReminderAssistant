package com.example.medicationreminderassistant.service;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.example.medicationreminderassistant.model.Medication;
import com.example.medicationreminderassistant.model.RecognizeResult;
import com.example.medicationreminderassistant.util.TimeUtils;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 处方识别服务：
 * 1. 用 ML Kit 中文 OCR 把处方照片转成文字；
 * 2. 用规则解析把整段文字拆成结构化的候选药品（名称/剂量/频次/时间点/备注）。
 * 规则解析不可能 100% 准确，因此结果一律进确认页由用户核对修改。
 */
public class RecognitionService {

    private static final String TAG = "RecognitionService";

    /** 剂量单位（mg/ml/g/片/粒…），顺序保证 mg/ml 优先于 g 匹配 */
    private static final Pattern DOSAGE_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(mg|μg|ug|微克|ml|g|片|粒|袋|支|滴|贴|喷|吸|IU|单位|丸|包)",
            Pattern.CASE_INSENSITIVE);

    /** 药品规格：如 "0.5g*4片/盒"、"600mg*12片/盒"、"8mg*30片/瓶" */
    private static final Pattern SPEC_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(mg|g|μg|ug|ml)\\s*[*×xX]\\s*(\\d+(?:\\.\\d+)?)\\s*(片|粒|包|袋|支|瓶|丸|贴)",
            Pattern.CASE_INSENSITIVE);

    /** 常见药物剂型关键词，出现即认为该行可能是药品行 */
    private static final Pattern DRUG_FORM_PATTERN = Pattern.compile(
            "胶囊|分散片|缓释片|控释片|泡腾片|咀嚼片|肠溶片|软胶囊|颗粒|口服液|滴丸|糖浆|"
                    + "注射液|滴眼液|滴眼剂|眼膏|软膏|乳膏|栓剂|贴剂|气雾剂|吸入剂|片");

    /** 用法关键词：药名提取到这里就截断（"口服"不排除"口服液"剂型） */
    private static final Pattern USAGE_CUT_PATTERN = Pattern.compile(
            "口服(?!液)|饭前|饭后|餐前|餐后|睡前|空腹|每日|每天|一日|一天|每次|遵医嘱|"
                    + "bid|tid|qd|qod|prn|sig|外用|滴眼|滴鼻",
            Pattern.CASE_INSENSITIVE);

    /** 处方单抬头/表头等非药品行 */
    private static final Pattern NON_MEDICINE_PATTERN = Pattern.compile(
            "医院|卫生院|社区卫生|诊所|科室|诊断|处方|医嘱|姓名|性别|年龄|岁|"
                    + "日期|电话|地址|医师|药师|审核|核对|调配|金额|费用|收费|发票|"
                    + "临床|过敏|条码|用法用量|备注|正文|机构|邮编|编号|页码|第.*页|共.*页");

    /** 行首序号/前导符号，如 "1." "2、" "Rp "（不含开括号，开括号由括注剥离处理） */
    private static final Pattern LEADING_NOISE = Pattern.compile(
            "^[\\sRrPpSsGg0-9.、．。)）%％-]+");

    private static final Pattern CHINESE_NAME = Pattern.compile(
            "[\\u4e00-\\u9fa5][\\u4e00-\\u9fa5A-Za-z0-9]{1,19}");

    private static final int MAX_RESULTS = 10;

    private final TextRecognizer recognizer;

    public interface RecognizeCallback {
        void onSuccess(RecognizeResult result);

        void onFailure(Exception e);
    }

    public RecognitionService() {
        recognizer = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build());
    }

    /** OCR + 解析，一次性返回结构化结果 */
    public void recognize(Context context, String imagePath, RecognizeCallback callback) {
        try {
            File file = new File(imagePath);
            Uri uri = Uri.fromFile(file);
            InputImage image = InputImage.fromFilePath(context, uri);

            recognizer.process(image)
                    .addOnSuccessListener(visionText -> {
                        String rawText = visionText.getText();
                        Log.d(TAG, "OCR 识别成功，文字长度: " + rawText.length());
                        callback.onSuccess(new RecognizeResult(rawText, parsePrescription(rawText)));
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "OCR 识别失败", e);
                        callback.onFailure(e);
                    });
        } catch (IOException e) {
            Log.e(TAG, "读取图片失败", e);
            callback.onFailure(e);
        }
    }

    /**
     * 规则解析处方文本。支持"两行式"处方（药品行 + 用法行）：
     * - 药品行提取药名（药品标准库校验+纠错）、规格、剂量；
     * - 纯用法行（"用法: 口服 每次50mg 每日三次"）合并进上一条药品，
     *   频次/时间点以用法行为准，剂量不足时结合上一条药品的规格换算。
     */
    public static List<Medication> parsePrescription(String rawText) {
        Map<String, Medication> result = new LinkedHashMap<>();
        if (rawText == null || rawText.isEmpty()) {
            return new ArrayList<>();
        }

        Medication lastDrug = null;
        Double lastSpecStrengthMg = null;
        String lastSpecFormUnit = null;

        String[] lines = rawText.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.length() < 2) {
                continue;
            }

            boolean hasDosage = DOSAGE_PATTERN.matcher(line).find();
            boolean hasForm = DRUG_FORM_PATTERN.matcher(line).find();
            boolean hasUsage = hasUsageKeyword(line);
            if (!hasDosage && !hasForm && !hasUsage) {
                continue;
            }
            // 表头类文字（处方/医嘱/诊断…）只在不含剂量数字时才整行排除
            if (NON_MEDICINE_PATTERN.matcher(line).find() && !hasDosage) {
                continue;
            }

            String name = extractName(line);
            boolean nameValid = name != null && name.length() >= 2
                    && !NON_MEDICINE_PATTERN.matcher(name).find()
                    && DrugDictionary.isPlausibleDrugName(name);

            if (!nameValid) {
                // 纯用法行：合并到上一条药品，不单独成药品
                if (hasUsage && lastDrug != null) {
                    mergeUsageInto(lastDrug, line, lastSpecStrengthMg, lastSpecFormUnit);
                }
                continue;
            }

            // 药品标准库模糊纠错：利巴书林颗粒 -> 利巴韦林颗粒
            name = DrugDictionary.correctName(name);
            if (result.containsKey(name)) {
                lastDrug = result.get(name);
                continue;
            }

            String dosage = buildFriendlyDosage(line);
            int frequency = TimeUtils.parseFrequency(line);
            ArrayList<String> times = TimeUtils.timesFromDescription(line);
            if (times.isEmpty()) {
                times = TimeUtils.defaultTimesForFrequency(frequency);
            }

            Medication medication = new Medication();
            medication.setName(name);
            medication.setDosage(dosage);
            medication.setTimes(times);
            medication.setNote(buildNote(line));
            medication.setEnabled(true);
            result.put(name, medication);

            lastDrug = medication;
            // 记录本条药品的规格，供后续用法行换算"每次X袋/片"
            Matcher spec = SPEC_PATTERN.matcher(line);
            if (spec.find()) {
                double value = Double.parseDouble(spec.group(1));
                String unit = spec.group(2).toLowerCase(Locale.ROOT);
                lastSpecStrengthMg = "g".equals(unit) ? value * 1000 : value;
                lastSpecFormUnit = spec.group(4);
            } else {
                lastSpecStrengthMg = null;
                lastSpecFormUnit = null;
            }

            if (result.size() >= MAX_RESULTS) {
                break;
            }
        }
        return new ArrayList<>(result.values());
    }

    private static boolean hasUsageKeyword(String line) {
        return line.contains("每日") || line.contains("每天")
                || line.contains("一日") || line.contains("一天")
                || line.contains("饭前") || line.contains("饭后")
                || line.contains("餐前") || line.contains("餐后") || line.contains("睡前")
                || line.contains("用法") || line.contains("口服") || line.contains("外用")
                || line.contains("每次");
    }

    private static boolean hasFrequencyInfo(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return line.contains("每日") || line.contains("每天")
                || line.contains("一日") || line.contains("一天")
                || lower.contains("bid") || lower.contains("tid")
                || lower.contains("qd") || lower.contains("qod");
    }

    /**
     * 把用法行信息合并进上一条药品：
     * 频次/时间点以用法行为准；剂量为空时结合药品规格换算（如 50mg/袋 -> "1袋(50mg)"）；
     * 备注补充口服/外用/冲服等。
     */
    private static void mergeUsageInto(Medication drug, String usageLine,
                                       Double specStrengthMg, String specFormUnit) {
        if (hasFrequencyInfo(usageLine)) {
            int freq = TimeUtils.parseFrequency(usageLine);
            ArrayList<String> times = TimeUtils.timesFromDescription(usageLine);
            if (times.isEmpty()) {
                times = TimeUtils.defaultTimesForFrequency(freq);
            }
            drug.setTimes(times);
        }

        if (drug.getDosage() == null || drug.getDosage().isEmpty()) {
            String dosage = buildFriendlyDosage(usageLine, specStrengthMg, specFormUnit);
            if (!dosage.isEmpty()) {
                drug.setDosage(dosage);
            }
        }

        String extra = buildNote(usageLine);
        if (!extra.isEmpty()) {
            String note = drug.getNote();
            if (note == null || note.isEmpty()) {
                drug.setNote(extra);
            } else if (!note.contains(extra)) {
                drug.setNote(note + "、" + extra);
            }
        }
    }

    /** 从行文本提取备注：饭前/饭后/睡前/口服/外用/冲服 */
    private static String buildNote(String line) {
        StringBuilder note = new StringBuilder();
        if (line.contains("饭前") || line.contains("餐前")) {
            note.append("饭前服用");
        } else if (line.contains("饭后") || line.contains("餐后")) {
            note.append("饭后服用");
        }
        if (line.contains("睡前")) {
            if (note.length() > 0) {
                note.append("、");
            }
            note.append("睡前服用");
        }
        boolean oral = line.contains("口服")
                || line.toLowerCase(Locale.ROOT).contains("p.o");
        if (oral && note.length() == 0) {
            note.append("口服");
        }
        if ((line.contains("外用") || line.contains("外涂") || line.contains("涂抹"))
                && note.length() == 0) {
            note.append("外用");
        }
        if (line.contains("冲服") || line.contains("冲泡")) {
            if (note.length() > 0) {
                note.append("、");
            }
            note.append("开水冲服");
        }
        return note.toString();
    }

    /** 去掉行首序号/括注后，在剂量单位/用法关键词之前提取中文药名 */
    private static String extractName(String line) {
        String cleaned = LEADING_NOISE.matcher(line).replaceFirst("").trim();
        // 去掉行首抬头，如 "处方："、"Rp："，再剥一次序号
        cleaned = cleaned.replaceAll("^(处方|医嘱|Rp)[:：]?\\s*", "").trim();
        cleaned = LEADING_NOISE.matcher(cleaned).replaceFirst("").trim();
        // 去掉行首括注，如 （省采）（重）[基]【国谈】
        cleaned = cleaned.replaceAll("^([(\\[【（][^)\\]】）]{1,8}[)\\]】）])+\\s*", "").trim();

        int cut = cleaned.length();
        Matcher dosage = DOSAGE_PATTERN.matcher(cleaned);
        if (dosage.find()) {
            cut = Math.min(cut, dosage.start());
        }
        Matcher usage = USAGE_CUT_PATTERN.matcher(cleaned);
        if (usage.find()) {
            cut = Math.min(cut, usage.start());
        }

        String head = cut < cleaned.length() ? cleaned.substring(0, cut) : cleaned;
        Matcher nameMatcher = CHINESE_NAME.matcher(head);
        if (nameMatcher.find()) {
            String name = nameMatcher.group().trim();
            // 去掉药名末尾误粘的数字编号，如 "盐酸溴己新片30s" -> "盐酸溴己新片"
            name = name.replaceAll("[0-9]+[a-zA-Z]*$", "").trim();
            return name.isEmpty() ? null : name;
        }
        return null;
    }

    /**
     * 从处方行提取"每次剂量"，优先给出通俗数量：
     * 片/粒/包等固体按规格换算 -> "2片(16mg)"；液体 -> "5ml"；
     * 无法换算时保留原始克数/毫克数。
     */
    /** 显式每次用量：如 "每次1粒"、"每次5ml"、"每次 2 片" */
    private static final Pattern PER_DOSE_PATTERN = Pattern.compile(
            "每次\\s*(\\d+(?:\\.\\d+)?)\\s*(片|粒|包|袋|支|丸|滴|ml|毫升)",
            Pattern.CASE_INSENSITIVE);

    private static String buildFriendlyDosage(String line) {
        return buildFriendlyDosage(line, null, null);
    }

    /**
     * 提取"每次剂量"，优先通俗数量。specStrengthMg/specFormUnit 为药品行已知规格，
     * 供纯用法行（如"每次50mg"）换算成"1袋(50mg)"。
     */
    private static String buildFriendlyDosage(String line, Double specStrengthMg,
                                              String specFormUnit) {
        // 0. 处方明确写了 "每次X片/粒/ml" 时优先使用，并尽量附带克数
        Matcher perDose = PER_DOSE_PATTERN.matcher(line);
        if (perDose.find()) {
            double value = Double.parseDouble(perDose.group(1));
            String unit = perDose.group(2).toLowerCase(Locale.ROOT);
            unit = "毫升".equals(perDose.group(2)) ? "ml" : unit;
            String gram = findGramDose(line);
            if (gram == null && specStrengthMg != null && specFormUnit != null
                    && unit.equals(specFormUnit.toLowerCase(Locale.ROOT))) {
                gram = formatCount(value * specStrengthMg / 1000) + "g";
            }
            if (gram != null && !"ml".equals(unit)) {
                return formatCount(value) + unit + "(" + gram + ")";
            }
            return formatCount(value) + unit;
        }

        // 1. 规格：优先用传入的药品行规格，没有则从本行解析
        Double strengthMg = specStrengthMg;
        String formUnit = specFormUnit;
        Matcher spec = SPEC_PATTERN.matcher(line);
        if (spec.find()) {
            double value = Double.parseDouble(spec.group(1));
            String unit = spec.group(2).toLowerCase(Locale.ROOT);
            formUnit = spec.group(4);
            if ("g".equals(unit)) {
                strengthMg = value * 1000;
            } else { // mg / μg / ug / ml 按数值直接参与比较
                strengthMg = value;
            }
        }

        // 2. 找"每次用量"候选：跳过规格里的数字（紧邻 * × 两侧的）
        Matcher m = DOSAGE_PATTERN.matcher(line);
        while (m.find()) {
            boolean precededByStar = m.start() > 0
                    && "*×xX".indexOf(line.charAt(m.start() - 1)) >= 0;
            boolean followedByStar = m.end() < line.length()
                    && "*×xX".indexOf(line.charAt(m.end())) >= 0;
            if (precededByStar || followedByStar) {
                continue;
            }
            double value = Double.parseDouble(m.group(1));
            String unit = m.group(2).toLowerCase(Locale.ROOT);

            // 本身就是通俗计数单位：直接使用，如 "每次2片"
            if (unit.equals("片") || unit.equals("粒") || unit.equals("包")
                    || unit.equals("袋") || unit.equals("支") || unit.equals("丸")) {
                return formatCount(value) + unit;
            }
            // 液体：明确毫升数
            if (unit.equals("ml")) {
                return formatCount(value) + "ml";
            }
            // 克/毫克：有规格时换算成片/粒，如 16mg ÷ 8mg/片 = 2片(16mg)
            if ("g".equals(unit) || "mg".equals(unit) || "μg".equals(unit) || "ug".equals(unit)) {
                double doseMg = "g".equals(unit) ? value * 1000 : value;
                String gramText = "g".equals(unit)
                        ? formatCount(value) + "g"
                        : formatCount(value) + "mg";
                if (strengthMg != null && strengthMg > 0 && formUnit != null) {
                    double count = doseMg / strengthMg;
                    if (count >= 0.1 && count <= 30) {
                        return formatCount(count) + formUnit + "(" + gramText + ")";
                    }
                }
                return gramText;
            }
        }
        return "";
    }

    /** 找行内非规格部分的克/毫克剂量文本，如 "0.3g"、"600mg"；没有返回 null */
    private static String findGramDose(String line) {
        Matcher m = DOSAGE_PATTERN.matcher(line);
        while (m.find()) {
            boolean precededByStar = m.start() > 0
                    && "*×xX".indexOf(line.charAt(m.start() - 1)) >= 0;
            boolean followedByStar = m.end() < line.length()
                    && "*×xX".indexOf(line.charAt(m.end())) >= 0;
            if (precededByStar || followedByStar) {
                continue;
            }
            String unit = m.group(2).toLowerCase(Locale.ROOT);
            if ("g".equals(unit) || "mg".equals(unit) || "μg".equals(unit) || "ug".equals(unit)) {
                return formatCount(Double.parseDouble(m.group(1)))
                        + ("g".equals(unit) ? "g" : "mg");
            }
        }
        return null;
    }

    /** 1.0 -> "1"；1.5 -> "1.5" */
    private static String formatCount(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** 释放识别器资源 */
    public void close() {
        if (recognizer != null) {
            recognizer.close();
        }
    }
}
