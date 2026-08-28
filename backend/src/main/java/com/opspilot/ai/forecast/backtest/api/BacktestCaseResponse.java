package com.opspilot.ai.forecast.backtest.api;

import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.backtest.BacktestCase;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 返回单日回测的预测、真实结果和命中状态，不暴露原始模型响应。 */
public record BacktestCaseResponse(
        UUID id,
        LocalDate asOfDate,
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
        OffsetDateTime createdAt
) {
    public static BacktestCaseResponse from(BacktestCase item) {
        return new BacktestCaseResponse(
                item.id(), item.asOfDate(), item.basePrice(),
                item.predictedDirection(), item.reasoning(),
                item.invalidationConditions(), item.targetDate(),
                item.targetPrice(), item.actualReturn(),
                item.actualDirection(), item.hit(), item.modelName(),
                item.promptVersion(), item.promptHash(), item.ruleVersion(),
                item.createdAt()
        );
    }
}
