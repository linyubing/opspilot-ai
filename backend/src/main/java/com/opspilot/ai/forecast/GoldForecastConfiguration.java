package com.opspilot.ai.forecast;

import com.opspilot.ai.forecast.backtest.review.BacktestReviewCacheProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册黄金方向预测所需的配置属性。 */
@Configuration
@EnableConfigurationProperties({
        GoldForecastProperties.class,
        BacktestReviewCacheProperties.class
})
public class GoldForecastConfiguration {
}
