package com.opspilot.ai.forecast.backtest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证回测任务能够冻结并识别校准版提示词。 */
class BacktestPromptVersionTests {

    @Test
    void supportsCalibratedPrompt() {
        var version = BacktestPromptVersion.valueOf("CALIBRATED");

        assertThat(version.version()).isEqualTo("gold-backtest-prompt-v4");
    }
}
