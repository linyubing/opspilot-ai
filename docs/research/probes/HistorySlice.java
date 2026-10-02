package com.opspilot.ai.forecast.learning;

import java.time.LocalDate;
import java.util.List;

/** 仅选择验证开始前已结算的真实OHLC训练样本。 */
final class HistorySlice {
    static List<TrainingProbe.Row> training(List<TrainingProbe.Row> rows, LocalDate start) {
        if (rows == null || rows.isEmpty() || start == null)
            throw new IllegalArgumentException("训练样本和验证起点不能为空");
        LocalDate previous = null;
        for (var row : rows) {
            if (row == null || row.date() == null || row.target() == null || row.label() == null
                    || row.x() == null || row.x().length != 20 || !row.target().isAfter(row.date())
                    || (previous != null && !row.date().isAfter(previous)))
                throw new IllegalArgumentException("训练样本维数或日期非法");
            for (double value : row.x())
                if (!Double.isFinite(value)) throw new IllegalArgumentException("特征必须为有限数");
            previous = row.date();
        }
        // 基准日早还不够：目标日等于验证起点的标签也尚不可用于训练。
        var train = rows.stream().filter(row -> row.date().isBefore(start) && row.target().isBefore(start)).toList();
        if (train.isEmpty()) throw new IllegalArgumentException("没有验证起点前已结算的样本");
        return train;
    }
}
