package com.opspilot.ai.forecast.learning;

import java.util.List;
import java.util.Map;

/** 保存成功构建的样本和因真实数据不完整而拒绝的数量。 */
public record GoldDataset(List<GoldSample> samples, int skippedCount, Map<String, String> macroInput) {
    /** 兼容人工组装/旧数据，明确标记不能证明历史可得性。 */
    public GoldDataset(List<GoldSample> samples, int skippedCount) {
        this(samples, skippedCount, Map.of("policy", "legacy-latest-version"));
    }

    public GoldDataset {
        samples = List.copyOf(samples);
        macroInput = Map.copyOf(macroInput);
        if (skippedCount < 0) {
            throw new IllegalArgumentException("拒绝样本数量不能为负数");
        }
    }
}
