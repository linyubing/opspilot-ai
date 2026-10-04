package com.opspilot.ai.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 保存与正式快照绑定、生成后不可覆盖的黄金方向预测。 */
public record StoredGoldDirectionForecast(
        UUID id, UUID snapshotId, LocalDate baseDate, BigDecimal basePrice,
        ForecastDirection predictedDirection, String reasoning,
        List<String> invalidationConditions, String modelName,
        String promptVersion, String promptHash, String forecastRuleVersion,
        String rawResponse, ForecastStatus status, LocalDate targetDate,
        BigDecimal targetPrice, BigDecimal actualReturn,
        ForecastDirection actualDirection, Boolean hit,
        OffsetDateTime resolvedAt, OffsetDateTime createdAt,
        GoldForecastTiming timing
) {
    /** 旧记录没有发布合同，保持未知，不能事后回填证据。 */
    public StoredGoldDirectionForecast(
            UUID id, UUID snapshotId, LocalDate baseDate, BigDecimal basePrice,
            ForecastDirection predictedDirection, String reasoning,
            List<String> invalidationConditions, String modelName,
            String promptVersion, String promptHash, String forecastRuleVersion,
            String rawResponse, ForecastStatus status, LocalDate targetDate,
            BigDecimal targetPrice, BigDecimal actualReturn,
            ForecastDirection actualDirection, Boolean hit,
            OffsetDateTime resolvedAt, OffsetDateTime createdAt) {
        this(id, snapshotId, baseDate, basePrice, predictedDirection, reasoning,
                invalidationConditions, modelName, promptVersion, promptHash, forecastRuleVersion,
                rawResponse, status, targetDate, targetPrice, actualReturn, actualDirection, hit,
                resolvedAt, createdAt, null);
    }
}
