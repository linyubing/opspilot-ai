package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.forecast.DirectionEvaluation;
import com.opspilot.ai.forecast.ForecastDirection;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** 根据已结算的历史回测明细计算独立评估指标。 */
@Service
public class BacktestEvaluationService {

    private static final int SCALE = 4;

    private final BacktestService service;
    private final BacktestRepository repo;

    public BacktestEvaluationService(
            BacktestService service,
            BacktestRepository repo
    ) {
        this.service = service;
        this.repo = repo;
    }

    public BacktestEvaluation evaluate(UUID id) {
        service.get(id);
        List<BacktestCase> cases = repo.findCases(id, 120);
        List<BacktestCase> latest = cases.stream()
                .sorted(Comparator.comparing(BacktestCase::createdAt).reversed())
                .limit(20)
                .toList();
        int neutralActual = (int) cases.stream()
                .filter(item -> item.actualDirection() == ForecastDirection.NEUTRAL)
                .count();

        return new BacktestEvaluation(
                "BACKTEST",
                cases.size(),
                accuracy(cases),
                accuracy(latest),
                ratio(neutralActual, cases.size()),
                direction(cases, ForecastDirection.BULLISH),
                direction(cases, ForecastDirection.NEUTRAL),
                direction(cases, ForecastDirection.BEARISH)
        );
    }

    private DirectionEvaluation direction(
            List<BacktestCase> cases,
            ForecastDirection direction
    ) {
        List<BacktestCase> selected = cases.stream()
                .filter(item -> item.predictedDirection() == direction)
                .toList();
        return new DirectionEvaluation(
                direction,
                selected.size(),
                hits(selected),
                accuracy(selected)
        );
    }

    private BigDecimal accuracy(List<BacktestCase> cases) {
        return ratio(hits(cases), cases.size());
    }

    private int hits(List<BacktestCase> cases) {
        return (int) cases.stream().filter(BacktestCase::hit).count();
    }

    private BigDecimal ratio(int count, int total) {
        if (total == 0) {
            return null;
        }
        return BigDecimal.valueOf(count).divide(
                BigDecimal.valueOf(total),
                SCALE,
                RoundingMode.HALF_UP
        );
    }
}
