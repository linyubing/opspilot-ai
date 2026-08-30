package com.opspilot.ai.forecast.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证黄金最新预测页面保留关键操作、结果和可信度入口。 */
class GoldForecastPageTests {

    @Test
    @DisplayName("页面可以生成并查看下一有效交易日预测")
    void showsNextSessionForecast() throws IOException {
        String html = resource("/static/forecast.html");
        String script = resource("/static/forecast.js");

        assertThat(html)
                .contains("下一有效交易日预测")
                .contains("同步最新数据并生成预测")
                .contains("历史回测准确率")
                .contains("真实黄金日线")
                .contains("id=\"barOpen\"")
                .contains("id=\"barHigh\"")
                .contains("id=\"barLow\"")
                .contains("id=\"barClose\"")
                .contains("id=\"basePriceLabel\"")
                .contains("forecast.js");
        assertThat(script)
                .contains("/api/research/gold/daily-report")
                .contains("/api/research/gold/daily-report/latest")
                .contains("/api/research/gold/forecasts?limit=1")
                .contains("/api/research/gold/forecasts/evaluation")
                .contains("/api/market-data/gold/daily-bars/latest")
                .contains("历史预测基准价（旧口径）")
                .contains("latestGoldDate")
                .contains("latestRealRateDate")
                .contains("latestDollarIndexDate")
                .contains("BULLISH: \"上涨\"")
                .contains("BEARISH: \"下跌\"");
    }

    private String resource(String path) throws IOException {
        try (var input = getClass().getResourceAsStream(path)) {
            if (input == null) {
                return "";
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
