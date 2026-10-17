package com.example.medicationreminderassistant.widget;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.example.medicationreminderassistant.R;
import com.example.medicationreminderassistant.model.Medication;
import com.example.medicationreminderassistant.service.MedicationDatabase;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

/**
 * 药品服药记录月历：
 * - 浅绿背景（由外层卡片提供）；
 * - 当天数达标（实际服用次数 ≥ 药品提醒次数）时，日期数字显示深绿色实心圆；
 * - 服用但次数未达标时，日期数字下方显示 "已服/应服" 小字；
 * - 今天用深绿描边圆圈标识；
 * - 支持左右切换月份，底部显示本月达标统计。
 */
public class MedicationCalendarView extends LinearLayout {

    private static final String[] WEEK_LABELS = {"日", "一", "二", "三", "四", "五", "六"};

    private MedicationDatabase database;
    private Medication medication;
    private int requiredDoses = 1;
    private YearMonth displayedMonth;

    private TextView tvMonthTitle;
    private TextView tvMonthSummary;
    private LinearLayout weeksContainer;

    public MedicationCalendarView(Context context) {
        super(context);
        init();
    }

    public MedicationCalendarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MedicationCalendarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOrientation(VERTICAL);
        database = MedicationDatabase.getInstance(getContext());
        displayedMonth = YearMonth.now();

        // ---------- 顶部：上一月 / 标题 / 下一月 ----------
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView btnPrev = buildNavButton("‹");
        tvMonthTitle = new TextView(getContext());
        tvMonthTitle.setGravity(Gravity.CENTER);
        tvMonthTitle.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        tvMonthTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvMonthTitle.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LayoutParams(0,
                LayoutParams.WRAP_CONTENT, 1f);
        header.addView(btnPrev);
        header.addView(tvMonthTitle, titleParams);
        header.addView(buildNavButton("›"));
        btnPrev.setOnClickListener(v -> {
            displayedMonth = displayedMonth.minusMonths(1);
            renderMonth();
        });
        ((TextView) header.getChildAt(2)).setOnClickListener(v -> {
            displayedMonth = displayedMonth.plusMonths(1);
            renderMonth();
        });
        addView(header);

        // ---------- 星期表头 ----------
        LinearLayout weekdayRow = new LinearLayout(getContext());
        weekdayRow.setOrientation(HORIZONTAL);
        weekdayRow.setGravity(Gravity.CENTER);
        for (String label : WEEK_LABELS) {
            TextView tv = new TextView(getContext());
            tv.setText(label);
            tv.setGravity(Gravity.CENTER);
            tv.setTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            weekdayRow.addView(tv, new LinearLayout.LayoutParams(0,
                    LayoutParams.WRAP_CONTENT, 1f));
        }
        LinearLayout.LayoutParams weekdayParams = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        weekdayParams.topMargin = dp(10);
        addView(weekdayRow, weekdayParams);

        // ---------- 日期网格（每周一行，7 个等宽单元格） ----------
        weeksContainer = new LinearLayout(getContext());
        weeksContainer.setOrientation(VERTICAL);
        addView(weeksContainer);

        // ---------- 本月统计 ----------
        tvMonthSummary = new TextView(getContext());
        tvMonthSummary.setGravity(Gravity.CENTER);
        tvMonthSummary.setTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        tvMonthSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams summaryParams = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = dp(8);
        addView(tvMonthSummary, summaryParams);
    }

    private TextView buildNavButton(String text) {
        TextView button = new TextView(getContext());
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(ContextCompat.getColor(getContext(), R.color.brand_primary));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        button.setPadding(dp(16), dp(4), dp(16), dp(4));
        button.setClickable(true);
        return button;
    }

    /** 绑定药品并渲染当前月 */
    public void setMedication(Medication med) {
        this.medication = med;
        this.requiredDoses = Math.max(1, med.getTimes() == null ? 1 : med.getTimes().size());
        this.displayedMonth = YearMonth.now();
        renderMonth();
    }

    /** 打卡后刷新当前月 */
    public void refresh() {
        renderMonth();
    }

    private void renderMonth() {
        if (medication == null) {
            return;
        }
        tvMonthTitle.setText(displayedMonth.getYear() + "年" + displayedMonth.getMonthValue() + "月");

        LocalDate first = displayedMonth.atDay(1);
        LocalDate last = displayedMonth.atEndOfMonth();
        Map<String, Integer> counts = database.getDoseCounts(
                medication.getId(), first.toString(), last.toString());
        LocalDate today = LocalDate.now();

        weeksContainer.removeAllViews();
        int leadBlanks = first.getDayOfWeek().getValue() % 7; // 周日开头：周日=0
        int daysInMonth = displayedMonth.lengthOfMonth();
        int totalCells = leadBlanks + daysInMonth;
        int rows = (totalCells + 6) / 7;

        int fullDays = 0;
        int partialDays = 0;
        int dayNumber = 1;

        for (int row = 0; row < rows; row++) {
            LinearLayout weekRow = new LinearLayout(getContext());
            weekRow.setOrientation(HORIZONTAL);
            for (int col = 0; col < 7; col++) {
                int cellIndex = row * 7 + col;
                if (cellIndex < leadBlanks || dayNumber > daysInMonth) {
                    weekRow.addView(buildEmptyCell());
                    continue;
                }
                LocalDate date = displayedMonth.atDay(dayNumber);
                Integer taken = counts.get(date.toString());
                int takenCount = taken == null ? 0 : taken;
                boolean full = takenCount >= requiredDoses;
                boolean partial = takenCount > 0 && !full;
                boolean future = date.isAfter(today);
                if (full) {
                    fullDays++;
                } else if (partial && !date.equals(today)) {
                    partialDays++;
                }
                weekRow.addView(buildDayCell(dayNumber, full, partial,
                        date.equals(today), future, takenCount));
                dayNumber++;
            }
            weeksContainer.addView(weekRow);
        }

        tvMonthSummary.setText("本月按时按次达标 " + fullDays + " 天"
                + (partialDays > 0 ? " · 未达标 " + partialDays + " 天" : ""));
    }

    private View buildEmptyCell() {
        View cell = new View(getContext());
        cell.setLayoutParams(new LinearLayout.LayoutParams(0, dp(52), 1f));
        return cell;
    }

    private View buildDayCell(int day, boolean full, boolean partial,
                              boolean isToday, boolean future, int takenCount) {
        LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f);
        cell.setLayoutParams(params);
        cell.setPadding(0, dp(4), 0, dp(4));

        TextView tvDay = new TextView(getContext());
        tvDay.setText(String.valueOf(day));
        tvDay.setGravity(Gravity.CENTER);
        tvDay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        int circleSize = dp(32);
        LinearLayout.LayoutParams dayParams = new LinearLayout.LayoutParams(circleSize, circleSize);
        tvDay.setLayoutParams(dayParams);

        TextView tvStatus = new TextView(getContext());
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        tvStatus.setLayoutParams(statusParams);

        if (full) {
            // 达标：深绿色实心圆 + 白色数字 + 下方对勾
            tvDay.setBackgroundResource(R.drawable.bg_circle_dark_green);
            tvDay.setTextColor(ContextCompat.getColor(getContext(), R.color.white));
            tvDay.setTypeface(null, Typeface.BOLD);
            tvStatus.setText("✓");
            tvStatus.setTextColor(ContextCompat.getColor(getContext(), R.color.brand_primary_dark));
        } else {
            if (isToday) {
                tvDay.setBackgroundResource(R.drawable.bg_circle_outline_green);
                tvDay.setTextColor(ContextCompat.getColor(getContext(), R.color.brand_primary_dark));
                tvDay.setTypeface(null, Typeface.BOLD);
            } else {
                tvDay.setTextColor(ContextCompat.getColor(getContext(),
                        future ? R.color.text_secondary : R.color.text_primary));
            }
            if (partial) {
                // 服用但次数未达标：日期下方显示“已服/应服”小字
                tvStatus.setText(takenCount + "/" + requiredDoses);
                tvStatus.setTextColor(ContextCompat.getColor(getContext(), R.color.danger));
            }
        }

        cell.addView(tvDay);
        cell.addView(tvStatus);
        return cell;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
