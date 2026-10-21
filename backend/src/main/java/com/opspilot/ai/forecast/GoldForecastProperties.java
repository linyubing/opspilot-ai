package com.opspilot.ai.forecast;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 保存黄金方向预测模型的专用配置。 */
@ConfigurationProperties("opspilot.forecast.gold")
public record GoldForecastProperties(String modelName, boolean requireRun) {
    // 有兼容构造器时，显式指定配置绑定入口，避免Spring寻找默认构造器。
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public GoldForecastProperties { }
    /** 旧配置未声明逐次观察能力，不能凭模型名补造。 */
    public GoldForecastProperties(String modelName) { this(modelName, false); }
}
