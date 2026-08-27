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

        int pendingCount =(int) allForecasts.stream().filter(forecast->
                forecast.status()==ForecastStatus.PENDING).count();

        DirectionEvaluation bullish = evaluateDirection(
                resolvedForecasts,
                ForecastDirection.BULLISH
        );
        DirectionEvaluation neutral = evaluateDirection(
                resolvedForecasts,
                ForecastDirection.NEUTRAL
        );
        DirectionEvaluation bearish = evaluateDirection(
                resolvedForecasts,
                ForecastDirection.BEARISH
        );

        return new GoldForecastEvaluation(
                allForecasts.size(),
                pendingCount,
                resolvedForecasts.size(),
                calculateAccuracy(resolvedForecasts),
                bullish,
                neutral,
                bearish,
                calculateRolling20Accuracy(resolvedForecasts),
                calculateNeutralBaselineAccuracy(resolvedForecasts),
                evaluateVersions(resolvedForecasts)
        );
    }
    /**
     * 严格按照模型、提示词和方向规则三个版本共同分组。
     */

    private List<ForecastVersionEvaluation> evaluateVersions(List<StoredGoldDirectionForecast> resolvedForecasts) {
        Map<VersionKey, List<StoredGoldDirectionForecast>> grouped =
                resolvedForecasts.stream()
                        .collect(Collectors.groupingBy(
                                forecast -> new VersionKey(
                                        forecast.modelName(),
                                        forecast.promptVersion(),
                                        forecast.forecastRuleVersion()
                                )
                        ));

        return grouped.entrySet().stream()
                /*
                 * Map 本身没有稳定顺序，返回 API 前必须确定排序，
                 * 否则相同数据可能产生不同的 JSON 数组顺序。
                 */
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    VersionKey version = entry.getKey();
                    List<StoredGoldDirectionForecast> forecasts =
                            entry.getValue();

                    return new ForecastVersionEvaluation(
                            version.modelName(),
                            version.promptVersion(),
                            version.forecastRuleVersion(),
                            forecasts.size(),
                            countHits(forecasts),
                            calculateAccuracy(forecasts)
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
     * 最近 20 条必须根据实际解析时间排序，
     * 不能依赖仓储当前返回顺序。
     */
    private BigDecimal calculateRolling20Accuracy(List<StoredGoldDirectionForecast> resolvedForecasts) {
        List<StoredGoldDirectionForecast> latestForecasts =
                resolvedForecasts.stream()
                        .sorted(Comparator.comparing(
                                StoredGoldDirectionForecast::resolvedAt
                        ).reversed())
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
     * 作为版本统计的组合键，三个字段有一个不同就属于不同版本。
     */
    private record VersionKey(
            String modelName,
            String promptVersion,
            String forecastRuleVersion
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

            return forecastRuleVersion.compareTo(
                    other.forecastRuleVersion
            );
        }
    }
}
