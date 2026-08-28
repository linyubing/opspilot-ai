package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.forecast.ForecastDirection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 保存一条与实时预测隔离的黄金历史回测结果。 */
public record BacktestCase(
        UUID id,
        UUID backtestId,
        LocalDate asOfDate,
        GoldResearchSnapshot snapshot,
        BigDecimal basePrice,
        ForecastDirection predictedDirection,
        String reasoning,
        List<String> invalidationConditions,
        LocalDate targetDate,
        BigDecimal targetPrice,
        BigDecimal actualReturn,
        ForecastDirection actualDirection,
        boolean hit,
        String modelName,
        String promptVersion,
        String promptHash,
        String ruleVersion,
        String rawResponse,
        OffsetDateTime createdAt
) {
}
