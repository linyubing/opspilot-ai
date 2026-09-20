package com.opspilot.ai.forecast.learning;

import java.util.List;
import java.util.Set;
import java.util.Objects;

/** 将近期已结算样本交给基础训练器，窗口统计不接触未来数据。 */
final class WindowGoldTrainer implements GoldTrainer {
    private final GoldTrainer delegate;
    private final int size;

    WindowGoldTrainer(GoldTrainer delegate, int size) {
        if (size < 1) throw new IllegalArgumentException("训练窗口必须大于零");
        this.delegate = Objects.requireNonNull(delegate);
        this.size = size;
    }

    @Override
    public String name() { return delegate.name() + "-window-" + size; }

    @Override
    public GoldClassifier train(List<GoldSample> samples, Set<String> names) {
        if (samples == null || samples.size() < size) {
            throw new IllegalArgumentException("已结算训练样本不足，不能填补训练窗口");
        }
        for (int i = 1; i < samples.size(); i++) {
            if (!samples.get(i).asOfDate().isAfter(samples.get(i - 1).asOfDate())) {
                throw new IllegalArgumentException("训练样本日期必须严格递增且不能重复");
            }
        }
        // 上游先排除尚未结算的标签，再取最近样本；基础训练器在这个子集上拟合标准化。
        return delegate.train(List.copyOf(samples.subList(samples.size() - size, samples.size())), names);
    }
}
