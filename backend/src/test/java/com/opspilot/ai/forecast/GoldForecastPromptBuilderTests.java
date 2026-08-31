package com.opspilot.ai.forecast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证黄金方向预测提示词的事实、边界和可追踪摘要。 */
class GoldForecastPromptBuilderTests {

    private final GoldForecastPromptBuilder builder =
            new GoldForecastPromptBuilder();

    @Test
    @DisplayName("提示词包含快照事实、三分类规则和安全边界")
    void includesFactsRuleAndSafetyBoundaries() {
        GoldForecastPrompt prompt = builder.build(
                GoldForecastTestFixtures.snapshot("4520.00894962")
        );

        assertThat(prompt.version())
                .isEqualTo("gold-direction-forecast-prompt-v2");
        assertThat(prompt.content())
                .contains(GoldForecastTestFixtures.SNAPSHOT_ID.toString())
                .contains("2026-08-21", "2026-08-26", "2026-08-25")
                .contains("0.1313", "3.8413", "11.7766")
                .contains("NEUTRAL", "SUPPORTIVE")
                .contains("大于 0.5%", "小于 -0.5%", "中性")
                .contains("不得生成新闻", "目标价", "概率", "仓位", "买卖建议");
        assertThat(prompt.sha256()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("提示词包含本次预测失败暴露出来的校正规则")
    void includesFailureReviewRules() {
        GoldForecastPrompt prompt = builder.build(
                GoldForecastTestFixtures.snapshot("4520.00894962")
        );

        assertThat(prompt.content())
                .contains("不得因为 20 期收益率为正就自动判断为 BULLISH")
                .contains("1 期收益率为负时，必须说明短线转弱风险")
                .contains("5 期和 1 期方向冲突时，必须降低方向确信度")
                .contains("美元指数数据明显滞后时，必须降低美元指数因子的权重")
                .contains("波动率较高时，优先考虑 NEUTRAL 或反转风险");
    }

    @Test
    @DisplayName("相同快照摘要稳定而事实变化会改变摘要")
    void hashesExactPromptContent() {
        GoldForecastPrompt first = builder.build(
                GoldForecastTestFixtures.snapshot("4520.00894962")
        );
        GoldForecastPrompt repeated = builder.build(
                GoldForecastTestFixtures.snapshot("4520.00894962")
        );
        GoldForecastPrompt changed = builder.build(
                GoldForecastTestFixtures.snapshot("4521.00000000")
        );

        assertThat(repeated.sha256()).isEqualTo(first.sha256());
        assertThat(changed.sha256()).isNotEqualTo(first.sha256());
    }
}
