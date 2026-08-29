package com.opspilot.ai.forecast.backtest;

/** 定义回测可选择的基准提示词和候选提示词版本。 */
public enum BacktestPromptVersion {
    BASELINE(BacktestPromptBuilder.VERSION),
    CANDIDATE(CandidateBacktestPromptBuilder.VERSION),
    IMPROVED(ImprovedBacktestPromptBuilder.VERSION);

    private final String version;

    BacktestPromptVersion(String version) {
        this.version = version;
    }

    public String version() {
        return version;
    }
}
