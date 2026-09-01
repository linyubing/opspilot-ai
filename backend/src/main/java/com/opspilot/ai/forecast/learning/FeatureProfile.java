package com.opspilot.ai.forecast.learning;

import java.util.Set;
import java.util.stream.Collectors;

/** 特征组合配置，用于消融实验。 */
public enum FeatureProfile {

    /** 阶段 4 之前的 16 个基础与宏观特征。 */
    BASE_16,
    /** 只使用新增的 20 个黄金 OHLC 技术特征。 */
    OHLC_20,
    /** 使用全部 36 个特征。 */
    ALL_36;

    /** 返回该组合使用的特征名称集合。 */
    public Set<String> featureNames() {
        return switch (this) {
            case BASE_16 -> GoldFeatures.NAMES.stream()
                    .filter(name -> !GoldOhlcFeatures.NAMES.contains(name))
                    .collect(Collectors.toUnmodifiableSet());
            case OHLC_20 -> GoldOhlcFeatures.NAMES;
            case ALL_36 -> GoldFeatures.NAMES;
        };
    }
}
