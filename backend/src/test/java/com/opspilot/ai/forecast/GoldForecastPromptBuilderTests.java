package com.opspilot.ai.forecast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.math.BigDecimal;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.RealRateChangeMetrics;
import com.opspilot.ai.analysis.DollarIndexChangeMetrics;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证黄金方向预测提示词的事实、边界和可追踪摘要。 */
class GoldForecastPromptBuilderTests {

    private final GoldForecastPromptBuilder builder =
            new GoldForecastPromptBuilder();

    private static final String BASE_RULE = "7. 波动率较高时，优先考虑 NEUTRAL 或反转风险。";
    private static final String CANDIDATE_RULE = "7. 波动率较高只表示价格变动可能更大，不能因此优先选择 NEUTRAL；"
            + "证据不确定不等于涨跌幅位于 [-0.5%, 0.5%]，仍须按三方向合同选择最有依据的类别，"
            + "并在 reasoning 中说明不确定性。";

    @Test
    @DisplayName("宏观候选保留正式版全文，只补充原快照的数值、单位及观测日期")
    void addsMacroFacts() {
        var input = GoldForecastTestFixtures.snapshot("4520.00894962");
        var base = builder.build(input);
        var macro = builder.buildMacro(input);
        assertThat(macro.content()).startsWith(base.content())
                .contains("实际利率观察日期：2026-08-25", "广义美元观察日期：2026-08-21")
                .contains("实际利率当前值（%）：2.400000")
                .contains("实际利率1期变化（基点）：5.00", "实际利率5期变化（基点）：-1.00",
                        "实际利率20期变化（基点）：-3.00")
                .contains("广义美元当前值（指数点）：118.062800")
                .contains("广义美元1期变化（%）：-0.1624", "广义美元5期变化（%）：-0.7065",
                        "广义美元20期变化（%）：-2.1934")
                .contains("不是自然日", "不是DXY", "未留存来源码");
        assertThat(macro.version()).isEqualTo("gold-macro-values-candidate-v1");
        assertThat(base.sha256()).isEqualTo("365f6f42689df76c50ecc6e863dd84ed64a3981867d5e188aea0ec89e9645d08");
    }

    @Test
    @DisplayName("宏观真实数值变化影响候选内容和摘要，不从因子标签推造数值")
    void tracksMacroChanges() {
        var input = GoldForecastTestFixtures.snapshot("4520.00894962");
        var rate = input.snapshot().realRate();
        var changedRate = new RealRateChangeMetrics(new BigDecimal("2.900000"),
                rate.percentagePointChange1(), rate.percentagePointChange5(), rate.percentagePointChange20(),
                rate.basisPointChange1(), rate.basisPointChange5(), rate.basisPointChange20(), rate.collectedAt());
        var changed = withMacro(input, changedRate, input.snapshot().dollarIndex());
        assertThat(builder.buildMacro(changed).content()).contains("实际利率当前值（%）：2.900000")
                .doesNotContain("实际利率当前值（%）：2.400000");
        assertThat(builder.buildMacro(changed).sha256()).isNotEqualTo(builder.buildMacro(input).sha256());
        assertThat(builder.build(changed).sha256()).isEqualTo(builder.build(input).sha256());
    }

    @Test
    @DisplayName("没有数值的旧快照显式标记缺失，不能补成零或反推来源码")
    void marksMissingMacroValues() {
        var input = GoldForecastTestFixtures.snapshot("4520.00894962");
        var missing = builder.buildMacro(withMacro(input, null, null));
        assertThat(missing.content()).contains("实际利率当前值（%）：缺失", "实际利率1期变化（基点）：缺失",
                "广义美元当前值（指数点）：缺失", "广义美元20期变化（%）：缺失")
                .doesNotContain("实际利率当前值（%）：0", "广义美元当前值（指数点）：0", "DFII10", "DTWEXBGS");
    }

    // 只改测试对象中的宏观数值；保留既有因子解释，用来发现错误缓存或反推数据。
    private StoredGoldResearchSnapshot withMacro(StoredGoldResearchSnapshot input,
            RealRateChangeMetrics rate, DollarIndexChangeMetrics dollar) {
        var s = input.snapshot();
        return new StoredGoldResearchSnapshot(input.id(), new GoldResearchSnapshot(
                s.analysisDate(), s.latestGoldDate(), s.latestRealRateDate(), s.latestDollarIndexDate(), s.gold(),
                rate, dollar, s.realRateAssessment(), s.dollarIndexAssessment(), s.researchVersion(),
                s.disclaimer(), s.input()), input.createdAt());
    }

    @Test
    @DisplayName("候选只改高波动规则，其他输入、方向合同和JSON安全边界逐字相同")
    void changesOneRule() {
        var input = GoldForecastTestFixtures.snapshot("4520.00894962");
        var base = builder.build(input);
        var candidate = builder.buildCandidate(input);
        assertThat(candidate.content()).contains(CANDIDATE_RULE).doesNotContain(BASE_RULE);
        assertThat(candidate.content().replace(CANDIDATE_RULE, BASE_RULE)).isEqualTo(base.content());
        assertThat(base.content()).contains(BASE_RULE).doesNotContain(CANDIDATE_RULE);
        assertThat(candidate.version()).isEqualTo("gold-neutral-contract-candidate-v1");
        assertThat(base.version()).isEqualTo("gold-direction-forecast-prompt-v2");
        // 固定原v2的完整UTF8摘要，防止共享模板无意改变正式提示词而仍沿用旧版本。
        assertThat(base.sha256()).isEqualTo("365f6f42689df76c50ecc6e863dd84ed64a3981867d5e188aea0ec89e9645d08");
    }

    @Test
    @DisplayName("候选摘要来自实际UTF8内容，不能复用正式版摘要或缓存旧事实")
    void hashesCandidate() throws Exception {
        var input = GoldForecastTestFixtures.snapshot("4520.00894962");
        var candidate = builder.buildCandidate(input);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(candidate.content().getBytes(StandardCharsets.UTF_8)));
        assertThat(candidate.sha256()).isEqualTo(hash).isNotEqualTo(builder.build(input).sha256());
        var changed = builder.buildCandidate(GoldForecastTestFixtures.snapshot("4521.00000000"));
        assertThat(changed.content()).contains("4521.00000000").doesNotContain("4520.00894962");
        assertThat(changed.sha256()).isNotEqualTo(candidate.sha256());
    }

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
