package com.example.medicationreminderassistant.util;

import android.text.TextUtils;

import com.example.medicationreminderassistant.model.Medication;
import com.example.medicationreminderassistant.service.MedicationDatabase;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 组装 AI 助手的系统提示词：应用内置安全约束 + 当前药品清单
 * + 最近 7 天服药依从性统计（每日实际次数/应服次数）。
 */
public class AiContextBuilder {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    public static String buildSystemPrompt(MedicationDatabase db) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是“用药提醒助手”应用内的健康管理小助手，根据用户的药品清单与服药记录，")
          .append("用通俗、友好、简短的中文提供服药管理与健康生活建议。\n\n")
          .append("【重要约束】\n")
          .append("1. 只依据下方“应用内数据”回答，不要编造用户没有记录的药品或病情；\n")
          .append("2. 涉及药物相互作用、剂量调整等专业问题时，必须提醒用户咨询医生或药师；\n")
          .append("3. 不做疾病诊断，不推荐具体处方药；\n")
          .append("4. 回答控制在 200 字以内，建议分点说明。\n\n")
          .append("【应用内数据】\n");

        List<Medication> meds = db.getAllMedications();
        if (meds.isEmpty()) {
            sb.append("用户尚未在应用内登记任何药品。可以提示用户先扫描处方添加药品。\n");
            return sb.toString();
        }

        LocalDate today = LocalDate.now();
        String fromDate = today.minusDays(6).format(FMT);
        String toDate = today.format(FMT);

        sb.append("一、当前药品清单（times 为每日提醒时间点）：\n");
        for (Medication m : meds) {
            sb.append("- ").append(m.getName());
            if (m.getDosage() != null && !m.getDosage().isEmpty()) {
                sb.append("（").append(m.getDosage()).append("）");
            }
            int timesCount = m.getTimes() == null ? 0 : m.getTimes().size();
            sb.append("，每日 ").append(timesCount).append(" 次");
            if (timesCount > 0) {
                sb.append("：").append(TextUtils.join("、", m.getTimes()));
            }
            if (m.getNote() != null && !m.getNote().isEmpty()) {
                sb.append("，备注：").append(m.getNote());
            }
            sb.append(m.isEnabled() ? "；\n" : "（已停用）；\n");
        }

        sb.append("\n二、最近 7 天服药情况（格式 日期=实际次数/应服次数，0 即漏服）：\n");
        for (Medication m : meds) {
            int expected = Math.max(1, m.getTimes() == null ? 1 : m.getTimes().size());
            Map<String, Integer> counts = db.getDoseCounts(m.getId(), fromDate, toDate);
            sb.append("- ").append(m.getName()).append(": ");
            for (int i = 6; i >= 0; i--) {
                LocalDate day = today.minusDays(i);
                int actual = counts.getOrDefault(day.format(FMT), 0);
                sb.append(day.format(FMT).substring(5)).append("=")
                  .append(actual).append("/").append(expected);
                if (i > 0) {
                    sb.append("，");
                }
            }
            sb.append("；\n");
        }
        return sb.toString();
    }
}
