package com.opspilot.ai.marketdata;

import com.opspilot.ai.forecast.GoldTradingCalendar;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 校验配置日历中的日线连续性；不认证供应商会话或闭市确认依据。 */
@Component
public class GoldBarWindow {

    private final GoldTradingCalendar calendar;

    public GoldBarWindow(GoldTradingCalendar calendar) {
        this.calendar = Objects.requireNonNull(calendar, "黄金交易日历不能为空");
    }

    /** 接受任意排序；重复日期、非交易日或缺日均不可作为连续窗口。 */
    public boolean isContinuous(List<GoldDailyBar> bars) {
        if (bars == null || bars.isEmpty()
                || bars.stream().anyMatch(bar -> bar == null || bar.priceDate() == null)) {
            return false;
        }
        List<GoldDailyBar> sorted = bars.stream()
                .sorted(Comparator.comparing(GoldDailyBar::priceDate))
                .toList();
        for (int i = 0; i < sorted.size(); i++) {
            var day = sorted.get(i).priceDate();
            if (!day.equals(calendar.nextBusinessDay(day.minusDays(1)))) {
                return false;
            }
            if (i > 0 && !day.equals(calendar.nextBusinessDay(sorted.get(i - 1).priceDate()))) {
                return false;
            }
        }
        return true;
    }
}
