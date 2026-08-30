package com.opspilot.ai.analysis;

import com.opspilot.ai.marketdata.MarketPrice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GoldVolatilityCalculatorTests {

    @Test
    void calculatesAnnualizedVolatility() {
        List<MarketPrice> prices = new ArrayList<>();
        LocalDate start = LocalDate.parse("2026-01-01");
        for (int index = 0; index < 21; index++) {
            String value = index % 2 == 0 ? "100" : "110";
            prices.add(price(start.plusDays(index), value));
        }
        Collections.reverse(prices);

        BigDecimal result = new GoldVolatilityCalculator().calculate(prices);

        assertThat(result).isEqualByComparingTo("151.3002");
    }

    private MarketPrice price(LocalDate date, String value) {
        return new MarketPrice(
                "XAUUSD",
                date,
                new BigDecimal(value),
                "usd",
                "troy_ounce",
                "test",
                OffsetDateTime.parse("2026-01-30T00:00:00Z")
        );
    }
}
