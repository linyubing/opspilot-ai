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

    /**
     * 计算指定回测任务的整体评估结果。
     */
    public BacktestEvaluation evaluate(UUID id) {
        // 先确认回测任务存在。
        service.get(id);

        List<BacktestCase> cases = repo.findCases(id, 120);

        // 最近创建的 20 条样本用于观察近期表现。
        List<BacktestCase> latest = cases.stream()
                .sorted(Comparator.comparing(
                        BacktestCase::createdAt
                ).reversed())
                .limit(20)
                .toList();

        int neutralActual = actualCount(
                cases,
                ForecastDirection.NEUTRAL
        );

        BigDecimal overall = accuracy(cases);
        BigDecimal baseline = majorityBaseline(cases);

        return new BacktestEvaluation(
                "BACKTEST",
                cases.size(),
                overall,
                accuracy(latest),
                ratio(neutralActual, cases.size()),
                baseline,
                lift(overall, baseline),
                balancedAccuracy(cases),
                matrix(cases),
                direction(cases, ForecastDirection.BULLISH),
                direction(cases, ForecastDirection.NEUTRAL),
                direction(cases, ForecastDirection.BEARISH)
        );
    }

    /**
     * 生成混淆矩阵。
     *
     * 每一行表示真实方向，行内三个数字表示模型分别预测成了什么。
     */
    private ConfusionMatrix matrix(List<BacktestCase> cases) {
        return new ConfusionMatrix(
                counts(cases, ForecastDirection.BULLISH),
                counts(cases, ForecastDirection.NEUTRAL),
                counts(cases, ForecastDirection.BEARISH)
        );
    }

    /**
     * 计算平衡准确率。
     *
     * 分别计算上涨、中性、下跌的召回率再求平均，
     * 避免多数类别掩盖模型在少数类别上的表现。
     */
    private BigDecimal balancedAccuracy(List<BacktestCase> cases) {
        BigDecimal[] recalls = {
                recall(cases, ForecastDirection.BULLISH),
                recall(cases, ForecastDirection.NEUTRAL),
                recall(cases, ForecastDirection.BEARISH)
        };

        BigDecimal total = BigDecimal.ZERO;
        int count = 0;

        for (BigDecimal value : recalls) {
            // 没有出现过的真实方向不参与平均。
            if (value != null) {
                total = total.add(value);
                count++;
            }
        }

        if (count == 0) {
            return null;
        }

        return total.divide(
                BigDecimal.valueOf(count),
                SCALE,
                RoundingMode.HALF_UP
        );
    }

    /**
     * 计算某个真实方向被模型正确识别的比例。
     */
    private BigDecimal recall(List<BacktestCase> cases, ForecastDirection actual) {
        int total = actualCount(cases, actual);
        int correct = count(cases, actual, actual);

        return ratio(correct, total);
    }

    /**
     * 统计真实方向和预测方向同时匹配的次数。
     */
    private int count(List<BacktestCase> cases, ForecastDirection actual, ForecastDirection predicted) {
        return (int) cases.stream()
                .filter(item ->
                        item.actualDirection() == actual
                )
                .filter(item ->
                        item.predictedDirection() == predicted
                )
                .count();
    }

    /**
     * 统计一个真实方向对应的三个预测方向数量。
     */
    private DirectionCounts counts(
            List<BacktestCase> cases,
            ForecastDirection actual
    ) {
        return new DirectionCounts(
                count(
                        cases,
                        actual,
                        ForecastDirection.BULLISH
                ),
                count(
                        cases,
                        actual,
                        ForecastDirection.NEUTRAL
                ),
                count(
                        cases,
                        actual,
                        ForecastDirection.BEARISH
                )
        );
    }

    /**
     * 计算模型相对于多数类别基线提升了多少。
     *
     * 结果大于零，表示模型胜过“永远猜最多类别”。
     */
    private BigDecimal lift(
            BigDecimal accuracy,
            BigDecimal baseline
    ) {
        if (accuracy == null || baseline == null) {
            return null;
        }

        return accuracy.subtract(baseline)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算多数类别基线。
     *
     * 含义：完全不分析，每次都猜历史数据中出现次数最多的方向，
     * 最终能够达到多少准确率。
     */
    private BigDecimal majorityBaseline(List<BacktestCase> cases) {
        int bullish = actualCount(
                cases,
                ForecastDirection.BULLISH
        );
        int neutral = actualCount(
                cases,
                ForecastDirection.NEUTRAL
        );
        int bearish = actualCount(
                cases,
                ForecastDirection.BEARISH
        );

        int max = Math.max(
                bullish,
                Math.max(neutral, bearish)
        );

        return ratio(max, cases.size());
    }

    /**
     * 统计某个真实方向出现的次数。
     */
    private int actualCount(List<BacktestCase> cases, ForecastDirection actual) {
        return (int) cases.stream()
                .filter(item ->
                        item.actualDirection() == actual
                )
                .count();
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
