package com.opspilot.ai.forecast;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.HashSet;
import java.util.Set;

/**
 * 基于配置的休市日列表和周末排除来判断黄金有效交易日。
 *
 * <p>固定休市日从 {@code opspilot.forecast.gold.holidays} 配置项读取，
 * 格式为 {@code --MM-dd}，例如 {@code ["--01-01","--07-04","--12-25"]}。
 * 周六、周日自动排除。
 */
@Component
@ConfigurationProperties(prefix = "opspilot.forecast.gold")
public class ConfiguredGoldTradingCalendar implements GoldTradingCalendar {

    private final Set<String> holidays = new HashSet<>();

    @Override
    public LocalDate nextBusinessDay(LocalDate from) {
        LocalDate date = from.plusDays(1);
        while (isHoliday(date) || isWeekend(date)) {
            date = date.plusDays(1);
        }
        return date;
    }

    private boolean isHoliday(LocalDate date) {
        MonthDay md = MonthDay.from(date);
        return holidays.stream().anyMatch(s -> MonthDay.parse(s).equals(md));
    }

    private boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    public Set<String> getHolidays() {
        return holidays;
    }

    public void setHolidays(Set<String> holidays) {
        this.holidays.clear();
        if (holidays != null) {
            this.holidays.addAll(holidays);
        }
    }
}
