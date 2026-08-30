package com.opspilot.ai.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TwelveDataGoldBarProviderTests {

    @Test
    void parsesDailyOhlc() throws Exception {
        JsonNode root = new ObjectMapper().readTree("""
                {
                  "meta": {"symbol": "XAU/USD", "interval": "1day"},
                  "values": [{
                    "datetime": "2026-08-28",
                    "open": "4601.3000",
                    "high": "4637.2000",
                    "low": "4444.6000",
                    "close": "4456.4000"
                  }],
                  "status": "ok"
                }
                """);
        TwelveDataGoldBarProvider provider =
                new TwelveDataGoldBarProvider();

        List<GoldDailyBar> bars = provider.parse(
                root,
                OffsetDateTime.parse("2026-08-31T00:00:00Z")
        );

        GoldDailyBar bar = bars.getFirst();
        assertThat(bar.priceDate().toString()).isEqualTo("2026-08-28");
        assertThat(bar.open()).isEqualByComparingTo("4601.3000");
        assertThat(bar.high()).isEqualByComparingTo("4637.2000");
        assertThat(bar.low()).isEqualByComparingTo("4444.6000");
        assertThat(bar.close()).isEqualByComparingTo("4456.4000");
        assertThat(bar.provider()).isEqualTo("twelve_data");
    }
}
