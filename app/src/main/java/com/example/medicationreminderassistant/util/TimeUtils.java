package com.example.medicationreminderassistant.util;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服药时间相关工具：频次转默认时间点、CSV 存取、中文数字解析、闹钟时刻计算。
 */
public final class TimeUtils {

    private static final Pattern DIGIT = Pattern.compile("\\d+");

    private TimeUtils() {
    }

    /** 每天服药次数 -> 均分的默认时间点 */
    public static ArrayList<String> defaultTimesForFrequency(int frequency) {
        ArrayList<String> times = new ArrayList<>();
        if (frequency <= 1) {
            times.add("09:00");
        } else if (frequency == 2) {
            times.addAll(Arrays.asList("08:00", "20:00"));
        } else if (frequency == 3) {
            times.addAll(Arrays.asList("08:00", "12:30", "18:30"));
        } else if (frequency == 4) {
            times.addAll(Arrays.asList("08:00", "12:00", "16:00", "20:00"));
        } else {
            // 超过 4 次：从 07:00 到 22:00 之间均分
            int start = 7 * 60;
            int end = 22 * 60;
            int step = (end - start) / (frequency - 1);
            for (int i = 0; i < frequency; i++) {
                int minutes = start + step * i;
                times.add(formatHourMinute(minutes / 60, minutes % 60));
            }
        }
        return times;
    }

    /** 把 "早/中/晚 + 饭前/饭后/睡前" 等描述映射为具体时间点 */
    public static ArrayList<String> timesFromDescription(String text) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (text == null) {
            return new ArrayList<>();
        }
        boolean beforeMeal = text.contains("饭前") || text.contains("餐前");
        boolean afterMeal = text.contains("饭后") || text.contains("餐后");
        // 没有明确饭前/饭后时按饭后时间（多数口服药随餐后服用）
        int mealOffset = beforeMeal ? -30 : 0;

        if (text.contains("睡前")) {
            set.add("21:30");
        }
        if (text.contains("早") || text.contains("晨") || text.contains("空腹")) {
            set.add(toHHmm(8, afterMeal || (!beforeMeal && text.contains("早")) ? 30 : 0, mealOffset, true));
        }
        if (text.contains("中") || text.contains("午")) {
            set.add(toHHmm(12, 30, mealOffset, true));
        }
        if (text.contains("晚")) {
            set.add(toHHmm(18, 30, mealOffset, true));
        }
        return new ArrayList<>(set);
    }

    private static String toHHmm(int hour, int minute, int offset, boolean applyOffset) {
        int total = hour * 60 + minute + (applyOffset ? offset : 0);
        int h = (total / 60) % 24;
        int m = ((total % 60) + 60) % 60;
        return formatHourMinute(h, m);
    }

    public static String formatHourMinute(int hour, int minute) {
        return String.format(Locale.getDefault(), "%02d:%02d", hour, minute);
    }

    /** 校验并归一化 "H:m" / "HH:mm"，非法返回 null */
    public static String normalizeTime(String input) {
        if (input == null) {
            return null;
        }
        Matcher m = Pattern.compile("(\\d{1,2})\\s*[:：]\\s*(\\d{1,2})").matcher(input.trim());
        if (!m.find()) {
            return null;
        }
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        if (h > 23 || min > 59) {
            return null;
        }
        return formatHourMinute(h, min);
    }

    /** 把 "08:00, 12:30" 这样的用户输入解析为时间点列表（去重、丢弃非法项） */
    public static ArrayList<String> parseTimesInput(String input) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (input != null) {
            for (String part : input.split("[,，、;；\\s]+")) {
                String t = normalizeTime(part);
                if (t != null) {
                    set.add(t);
                }
            }
        }
        return new ArrayList<>(set);
    }

    public static String timesToCsv(List<String> times) {
        if (times == null || times.isEmpty()) {
            return "";
        }
        return String.join(",", times);
    }

    public static ArrayList<String> timesFromCsv(String csv) {
        ArrayList<String> list = new ArrayList<>();
        if (csv != null && !csv.isEmpty()) {
            for (String part : csv.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    list.add(t);
                }
            }
        }
        return list;
    }

    public static String formatTimes(List<String> times) {
        if (times == null || times.isEmpty()) {
            return "未设置提醒时间";
        }
        return String.join("、", times);
    }

    /** 解析 "每日3次 / 一天两次 / qd" 等表述中的次数，无法识别时返回 1 */
    public static int parseFrequency(String text) {
        if (text == null) {
            return 1;
        }
        String t = text.toLowerCase(Locale.getDefault());
        if (t.contains("隔日") || t.contains("qod")) {
            return 1;
        }
        Matcher m = Pattern.compile("(?:每日|每天|一日|一天)\\s*([0-9零一二三四五六七八九十两]+)\\s*次").matcher(text);
        if (m.find()) {
            return Math.max(1, parseChineseOrInt(m.group(1)));
        }
        if (t.contains("tid") || text.contains("每日三次") || text.contains("一天三次")) {
            return 3;
        }
        if (t.contains("bid") || text.contains("每日两次") || text.contains("一天两次")
                || text.contains("每日二次") || text.contains("一天二次")) {
            return 2;
        }
        if (t.contains("qd") || text.contains("每日一次") || text.contains("一天一次")) {
            return 1;
        }
        if (text.contains("早") && text.contains("中") && text.contains("晚")) {
            return 3;
        }
        return 1;
    }

    /** "3" -> 3，"三" -> 3，"两" -> 2，"十" -> 10，"十二" -> 12 */
    public static int parseChineseOrInt(String token) {
        if (token == null) {
            return 1;
        }
        Matcher digit = DIGIT.matcher(token);
        if (digit.find()) {
            try {
                return Math.max(1, Integer.parseInt(digit.group()));
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        String t = token.trim();
        if (t.contains("两")) {
            return 2;
        }
        int shi = t.indexOf('十');
        if (shi >= 0) {
            int left = 1;
            int right = 0;
            if (shi > 0) {
                left = singleDigit(t.charAt(shi - 1));
            }
            if (shi + 1 < t.length()) {
                right = singleDigit(t.charAt(shi + 1));
            }
            return Math.max(1, left * 10 + right);
        }
        for (int i = 0; i < t.length(); i++) {
            int d = singleDigit(t.charAt(i));
            if (d > 0) {
                return d;
            }
        }
        return 1;
    }

    private static int singleDigit(char c) {
        switch (c) {
            case '一': return 1;
            case '二': return 2;
            case '两': return 2;
            case '三': return 3;
            case '四': return 4;
            case '五': return 5;
            case '六': return 6;
            case '七': return 7;
            case '八': return 8;
            case '九': return 9;
            default: return 0;
        }
    }

    /** 计算某时间点在 dayOffset 天后（0=今天）的触发毫秒值 */
    public static long nextTriggerMillis(String hhmm, int dayOffset) {
        String[] parts = hhmm.split(":");
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, dayOffset);
        calendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(parts[0]));
        calendar.set(Calendar.MINUTE, Integer.parseInt(parts[1]));
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
    }
}
