package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.forecast.ForecastDirection;

/** 保存一次因子诊断所需的研究快照和真实方向。 */
record FactorSample(
        GoldResearchSnapshot snapshot,
        ForecastDirection actual
) {
}
