package com.opspilot.ai.forecast;

import java.util.List;

/**
 * 描述一条已结算且未命中的预测失败原因，供前端向用户解释。
 */
public record GoldForecastMissReason(
        String code,
        String title,
        String detail,
        List<String> tags
) {
}
