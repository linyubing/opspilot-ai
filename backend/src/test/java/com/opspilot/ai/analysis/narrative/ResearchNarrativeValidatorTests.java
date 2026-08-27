package com.opspilot.ai.analysis.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchNarrativeValidatorTests {

    private final ResearchNarrativeValidator validator =
            new ResearchNarrativeValidator();

    @Test
    @DisplayName("接受结构完整且不包含交易指令的研究解读")
    void acceptsSafeContent() {
        assertThatCode(() -> validator.validate(safeContent()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("拒绝空对象和空白必填字段")
    void rejectsMissingContent() {
        assertUnsafe(null);
        assertUnsafe(copy(safeContent(), " ", null, null, null, null, null));
        assertUnsafe(copy(safeContent(), null, " ", null, null, null, null));
        assertUnsafe(copy(safeContent(), null, null, " ", null, null, null));
        assertUnsafe(copy(safeContent(), null, null, null, null, null, " "));
    }

    @Test
    @DisplayName("拒绝超过上限的摘要和因子分析")
    void rejectsOversizedText() {
        assertUnsafe(copy(safeContent(), "金".repeat(501), null, null, null, null, null));
        assertUnsafe(copy(safeContent(), null, "金".repeat(2001), null, null, null, null));
        assertUnsafe(copy(safeContent(), null, null, "金".repeat(2001), null, null, null));
        assertUnsafe(copy(safeContent(), null, null, null, null, null, "金".repeat(501)));
    }

    @Test
    @DisplayName("拒绝为空、超过五项或含超长单项的列表")
    void rejectsInvalidLists() {
        assertUnsafe(copy(safeContent(), null, null, null, List.of(), null, null));
        assertUnsafe(copy(safeContent(), null, null, null, null, List.of(), null));
        assertUnsafe(copy(safeContent(), null, null, null,
                List.of("1", "2", "3", "4", "5", "6"), null, null));
        assertUnsafe(copy(safeContent(), null, null, null, null,
                List.of("1", "2", "3", "4", "5", "6"), null));
        assertUnsafe(copy(safeContent(), null, null, null,
                List.of("风".repeat(301)), null, null));
        assertUnsafe(copy(safeContent(), null, null, null, null,
                List.of("看".repeat(301)), null));
    }

    @ParameterizedTest(name = "拒绝越界文本：{0}")
    @ValueSource(strings = {
            "建议买入黄金",
            "建议卖出黄金",
            "目标价 5000 美元",
            "止损位 4300 美元",
            "上涨概率为 70%"
    })
    @DisplayName("拒绝行动指令和数值化涨跌概率")
    void rejectsActionableLanguage(String unsafeText) {
        assertUnsafe(copy(safeContent(), unsafeText, null, null, null, null, null));
    }

    @Test
    @DisplayName("免责声明必须同时排除价格预测、交易和投资建议")
    void requiresCompleteDisclaimer() {
        assertUnsafe(copy(safeContent(), null, null, null, null, null,
                "本内容仅供研究参考。"));
    }

    private void assertUnsafe(ResearchNarrativeContent content) {
        assertThatThrownBy(() -> validator.validate(content))
                .isInstanceOf(UnsafeResearchNarrativeException.class);
    }

    /** 固定文本只验证安全合同，不代表真实行情、观点或投资结论。 */
    private ResearchNarrativeContent safeContent() {
        return new ResearchNarrativeContent(
                "实际利率中性，美元走弱对黄金构成单因子支持。",
                "实际利率变化有限，当前规则状态为中性。",
                "广义美元指数走弱，当前规则状态为支持。",
                List.of("各数据源的最新日期可能不完全一致。"),
                List.of("继续观察实际利率和广义美元指数的变化。"),
                "本解读不构成价格预测、交易或投资建议。"
        );
    }

    /** 非空参数替换原字段，便于每个测试只突出一个越界条件。 */
    private ResearchNarrativeContent copy(
            ResearchNarrativeContent source,
            String summary,
            String realRateAnalysis,
            String dollarIndexAnalysis,
            List<String> risks,
            List<String> watchList,
            String disclaimer
    ) {
        return new ResearchNarrativeContent(
                summary == null ? source.summary() : summary,
                realRateAnalysis == null ? source.realRateAnalysis() : realRateAnalysis,
                dollarIndexAnalysis == null ? source.dollarIndexAnalysis() : dollarIndexAnalysis,
                risks == null ? source.risks() : risks,
                watchList == null ? source.watchList() : watchList,
                disclaimer == null ? source.disclaimer() : disclaimer
        );
    }
}
