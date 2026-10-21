package com.opspilot.ai.forecast;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 基于数据库中已解析的真实结果计算黄金方向预测表现。 */
@Service
public class GoldForecastEvaluationService {

    private static final int ACCURACY_SCALE = 4;
    private static final int ROLLING_WINDOW_SIZE = 20;

    private final GoldForecastRepository forecastRepository;

    public GoldForecastEvaluationService(
            GoldForecastRepository forecastRepository
    ) {
        this.forecastRepository = forecastRepository;
    }

    /**
     * 统计黄金方向预测的总体、方向、滚动窗口和版本表现。
     *
     * @return 当前数据库中的预测表现
     */
    public GoldForecastEvaluation evaluate() {
        List<StoredGoldDirectionForecast> allForecasts = forecastRepository.findAllForEvaluation();

        /*
         * 只有 resolved 记录具有真实目标价格和实际方向，
         * pending、data_missing、voided 都不能进入准确率分母。
         */
        List<StoredGoldDirectionForecast> resolvedForecasts = allForecasts.stream().filter(this::isResolved).toList();
        List<StoredGoldDirectionForecast> scoredSignals = resolvedForecasts.stream()
                .filter(GoldSignalEvaluation::isScoredSignal).toList();

        int pendingCount =(int) allForecasts.stream().filter(forecast->
                forecast.status()==ForecastStatus.PENDING).count();

        DirectionEvaluation bullish = evaluateDirection(
                scoredSignals,
                ForecastDirection.BULLISH
        );
        DirectionEvaluation neutral = evaluateDirection(
                scoredSignals,
                ForecastDirection.NEUTRAL
        );
        DirectionEvaluation bearish = evaluateDirection(
                scoredSignals,
                ForecastDirection.BEARISH
        );

        return new GoldForecastEvaluation(
                allForecasts.size(),
                pendingCount,
                resolvedForecasts.size(),
                calculateAccuracy(scoredSignals),
                bullish,
                neutral,
                bearish,
                calculateRolling20Accuracy(scoredSignals),
                calculateNeutralBaselineAccuracy(scoredSignals),
                evaluateVersions(allForecasts),
                GoldForecastTimingEvaluation.from(resolvedForecasts),
                GoldTargetEvaluation.from(allForecasts),
                GoldSignalEvaluation.from(allForecasts)
        );
    }
    /**
     * 按模型、提示词、方向规则及响应标识分组；未知响应不借用已核对成绩。
     */

    private List<ForecastVersionEvaluation> evaluateVersions(List<StoredGoldDirectionForecast> allForecasts) {
        Map<VersionKey, List<StoredGoldDirectionForecast>> grouped =
                allForecasts.stream()
                        .collect(Collectors.groupingBy(
                                forecast -> new VersionKey(
                                        forecast.modelName(),
                                        forecast.promptVersion(),
                                        forecast.forecastRuleVersion(), forecast.reportedModel()
                                )
                        ));

        return grouped.entrySet().stream()
                // 保留旧接口仅列已开始结算版本的行为，但覆盖率必须保留同版本待结算记录。
                .filter(entry -> entry.getValue().stream().anyMatch(this::isResolved))
                /*
                 * Map 本身没有稳定顺序，返回 API 前必须确定排序，
                 * 否则相同数据可能产生不同的 JSON 数组顺序。
                 */
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    VersionKey version = entry.getKey();
                    List<StoredGoldDirectionForecast> forecasts =
                            entry.getValue();
                    var scored = forecasts.stream().filter(GoldSignalEvaluation::isScoredSignal).toList();

                    return new ForecastVersionEvaluation(
                            version.modelName(),
                            version.promptVersion(),
                            version.forecastRuleVersion(),
                            scored.size(),
                            countHits(scored),
                            calculateAccuracy(scored),
                            GoldSignalEvaluation.from(forecasts), version.reportedModel()
                    );
                })
                .toList();
    }

    /**
     * 中性基线表示：如果所有样本都预测为中性，能够命中的比例。
     */
    private BigDecimal calculateNeutralBaselineAccuracy(List<StoredGoldDirectionForecast> resolvedForecasts) {
        if (resolvedForecasts.isEmpty()) {
            return null;
        }

        int neutralActualCount = (int) resolvedForecasts.stream()
                .filter(forecast ->
                        forecast.actualDirection()
                                == ForecastDirection.NEUTRAL
                )
                .count();

        return divide(neutralActualCount, resolvedForecasts.size());
    }

    /**
     * 最近20条按行情日期排序，补结算旧预测不能改变最近行情窗口。
     * 相同日期以基准日和固定编号排序，不依赖仓储返回顺序。
     */
    private BigDecimal calculateRolling20Accuracy(List<StoredGoldDirectionForecast> resolvedForecasts) {
        List<StoredGoldDirectionForecast> latestForecasts =
                resolvedForecasts.stream()
                        .sorted(Comparator.comparing(
                                StoredGoldDirectionForecast::targetDate,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                                .thenComparing(StoredGoldDirectionForecast::baseDate,
                                        Comparator.nullsLast(Comparator.reverseOrder()))
                                .thenComparing(StoredGoldDirectionForecast::id))
                        .limit(ROLLING_WINDOW_SIZE)
                        .toList();

        return calculateAccuracy(latestForecasts);
    }

    /**
     * 统计某个预测方向的样本数和命中率。
     */
    private DirectionEvaluation evaluateDirection(List<StoredGoldDirectionForecast> resolvedForecasts, ForecastDirection direction) {
        /*
         * 这里按 predictedDirection 分组，
         * 回答的是“模型预测某个方向时有多准”。
         */
        List<StoredGoldDirectionForecast> directionForecasts = resolvedForecasts.stream()
                .filter(forecast ->
                forecast.predictedDirection() == direction).toList();

        return new DirectionEvaluation(
                direction,
                directionForecasts.size(),
                countHits(directionForecasts),
                calculateAccuracy(directionForecasts)
        );

    }

    private BigDecimal calculateAccuracy(List<StoredGoldDirectionForecast> forecasts) {
        if (forecasts.isEmpty()) {
            return null;
        }

        return divide(countHits(forecasts), forecasts.size());
    }

    /**
     * 准确率统一保留 4 位小数，例如 2/3 返回 0.6667。
     */
    private BigDecimal divide(int numerator, int denominator) {
        return BigDecimal.valueOf(numerator)
                .divide(
                        BigDecimal.valueOf(denominator),
                        ACCURACY_SCALE,
                        RoundingMode.HALF_UP
                );
    }

    private int countHits(List<StoredGoldDirectionForecast> forecasts) {
        return (int) forecasts.stream()
                .filter(forecast ->
                        Boolean.TRUE.equals(forecast.hit())
                )
                .count();
    }

    /**
     * 判断预测是否已经通过真实价格完成解析。
     */
    private boolean isResolved(
            StoredGoldDirectionForecast forecast
    ) {
        return forecast.status() == ForecastStatus.RESOLVED;
    }

    /**
     * 组合键还区分响应标识未知；这不是模型权重工件的不可变身份。
     */
    private record VersionKey(
            String modelName,
            String promptVersion,
            String forecastRuleVersion, String reportedModel
    ) implements Comparable<VersionKey> {

        @Override
        public int compareTo(VersionKey other) {
            int modelComparison =
                    modelName.compareTo(other.modelName);

            if (modelComparison != 0) {
                return modelComparison;
            }

            int promptComparison =
                    promptVersion.compareTo(other.promptVersion);

            if (promptComparison != 0) {
                return promptComparison;
            }

            int ruleComparison = forecastRuleVersion.compareTo(other.forecastRuleVersion);
            return ruleComparison != 0 ? ruleComparison
                    : Comparator.nullsFirst(String::compareTo).compare(reportedModel, other.reportedModel);
        }
    }
}
