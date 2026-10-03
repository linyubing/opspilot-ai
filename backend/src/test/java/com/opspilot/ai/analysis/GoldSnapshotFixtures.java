package com.opspilot.ai.analysis;

import com.opspilot.ai.marketdata.GoldBarConfirmation;
import com.opspilot.ai.marketdata.GoldDailyBar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** 恒价数学窗口只用于验证留痕边界，不是行情或预测效果。 */
public final class GoldSnapshotFixtures {
    private GoldSnapshotFixtures() { }

    public static GoldResearchSnapshot withInput(GoldResearchSnapshot source, LocalDate date,
            String version, OffsetDateTime checkedAt) {
        BigDecimal price = source.gold().currentPrice();
        List<GoldDailyBar> bars = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            bars.add(new GoldDailyBar("XAUUSD", date.minusDays(i), price, price, price, price,
                    "usd", "troy_ounce", "twelve_data", source.gold().collectedAt(),
                    new GoldBarConfirmation(GoldBarConfirmation.SOURCE, date, checkedAt, "a".repeat(64))));
        }
        GoldReturnMetrics gold = new GoldReturnMetrics(price, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, source.gold().collectedAt());
        return new GoldResearchSnapshot(date, date, source.latestRealRateDate(),
                source.latestDollarIndexDate(), gold, source.realRate(), source.dollarIndex(),
                source.realRateAssessment(), source.dollarIndexAssessment(), version, source.disclaimer(),
                new GoldSnapshotInput(bars, checkedAt));
    }
}
