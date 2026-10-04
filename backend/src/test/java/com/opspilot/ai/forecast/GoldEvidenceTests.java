package com.opspilot.ai.forecast;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.opspilot.ai.forecast.GoldEvidenceForecast.Trend.*;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 防止模型把原始数值读反、漏项或补造，同时保留原安全检查。 */
class GoldEvidenceTests {
    private final GoldForecastValidator validator = new GoldForecastValidator();
    private final GoldResearchSnapshot snapshot = GoldForecastTestFixtures.snapshot("4520.00894962").snapshot();

    @Test
    @DisplayName("独立引用候选要求九项原值及符号，不改变已有正式提示词")
    void buildsEvidencePrompt() {
        var builder = new GoldForecastPromptBuilder();
        var record = GoldForecastTestFixtures.snapshot("4520.00894962");
        var prompt = builder.buildEvidence(record);
        org.assertj.core.api.Assertions.assertThat(prompt.content())
                .contains("\"forecast\":", "\"evidence\":", "gold1,gold5,gold20,rate1,rate5,rate20,usd1,usd5,usd20")
                .contains("UP/DOWN/FLAT/MISSING", "不是未来预测方向", "不得改写原值")
                .contains("实际利率1期变化（基点）：5.00", "广义美元1期变化（%）：-0.1624");
        org.assertj.core.api.Assertions.assertThat(prompt.version()).isEqualTo("gold-evidence-candidate-v1");
        org.assertj.core.api.Assertions.assertThat(builder.build(record).sha256())
                .isEqualTo("365f6f42689df76c50ecc6e863dd84ed64a3981867d5e188aea0ec89e9645d08");
    }

    @Test
    @DisplayName("空输入和null引用项明确拒绝，不抛无说明的空指针异常")
    void rejectsNull() {
        assertThatThrownBy(() -> validator.validateEvidence(snapshot, null))
                .isInstanceOf(UnsafeGoldForecastException.class);
        var evidence = facts();
        evidence.set(0, null);
        assertThatThrownBy(() -> validator.validateEvidence(snapshot, result(evidence)))
                .isInstanceOf(UnsafeGoldForecastException.class);
    }

    @Test
    @DisplayName("真实零变化必须引用FLAT，不能标成缺失或上涨")
    void checksZero() {
        var r = snapshot.realRate();
        var rate = new com.opspilot.ai.analysis.RealRateChangeMetrics(r.currentRate(),
                BigDecimal.ZERO, r.percentagePointChange5(), r.percentagePointChange20(),
                BigDecimal.ZERO, r.basisPointChange5(), r.basisPointChange20(), r.collectedAt());
        var s = snapshot;
        var zero = new GoldResearchSnapshot(s.analysisDate(), s.latestGoldDate(), s.latestRealRateDate(),
                s.latestDollarIndexDate(), s.gold(), rate, s.dollarIndex(), s.realRateAssessment(),
                s.dollarIndexAssessment(), s.researchVersion(), s.disclaimer(), s.input());
        var evidence = facts();
        evidence.set(3, fact("rate1", "0.000", FLAT));
        assertThatCode(() -> validator.validateEvidence(zero, result(evidence))).doesNotThrowAnyException();
        evidence.set(3, fact("rate1", "0", UP));
        assertThatThrownBy(() -> validator.validateEvidence(zero, result(evidence)))
                .isInstanceOf(UnsafeGoldForecastException.class).hasMessageContaining("rate1");
        evidence.set(3, new GoldEvidenceForecast.Fact("rate1", null, MISSING));
        assertThatThrownBy(() -> validator.validateEvidence(zero, result(evidence)))
                .isInstanceOf(UnsafeGoldForecastException.class).hasMessageContaining("rate1");
    }

    @Test
    @DisplayName("逐项真实数值与变化方向一致时允许通过，精度尾零不改变数值含义")
    void acceptsFacts() {
        assertThatCode(() -> validator.validateEvidence(snapshot, result(facts()))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("实际利率正变化说成下跌、美元负变化说成上涨均拒绝")
    void rejectsReversedSign() {
        var rate = facts();
        rate.set(3, fact("rate1", "5.0", DOWN));
        assertThatThrownBy(() -> validator.validateEvidence(snapshot, result(rate)))
                .isInstanceOf(UnsafeGoldForecastException.class).hasMessageContaining("rate1");
        var dollar = facts();
        dollar.set(6, fact("usd1", "-0.1624", UP));
        assertThatThrownBy(() -> validator.validateEvidence(snapshot, result(dollar)))
                .isInstanceOf(UnsafeGoldForecastException.class).hasMessageContaining("usd1");
    }

    @Test
    @DisplayName("引用值与快照不一致时拒绝，不能改数字后保留相同符号蒙混通过")
    void rejectsChangedValue() {
        var evidence = facts();
        evidence.set(0, fact("gold1", "999", UP));
        assertThatThrownBy(() -> validator.validateEvidence(snapshot, result(evidence)))
                .isInstanceOf(UnsafeGoldForecastException.class).hasMessageContaining("gold1");
    }

    @Test
    @DisplayName("遗漏、重复和未知字段都拒绝，不能挑选有利事实")
    void rejectsBadKeys() {
        var missing = facts();
        missing.removeLast();
        assertThatThrownBy(() -> validator.validateEvidence(snapshot, result(missing)))
                .isInstanceOf(UnsafeGoldForecastException.class);
        var duplicate = facts();
        duplicate.set(8, duplicate.getFirst());
        assertThatThrownBy(() -> validator.validateEvidence(snapshot, result(duplicate)))
                .isInstanceOf(UnsafeGoldForecastException.class);
        var unknown = facts();
        unknown.set(8, fact("DXY", "100", UP));
        assertThatThrownBy(() -> validator.validateEvidence(snapshot, result(unknown)))
                .isInstanceOf(UnsafeGoldForecastException.class);
    }

    @Test
    @DisplayName("缺失宏观数据必须引用null和MISSING，不能补零或当作横盘")
    void keepsMissing() {
        var s = snapshot;
        var missing = new GoldResearchSnapshot(s.analysisDate(), s.latestGoldDate(), s.latestRealRateDate(),
                s.latestDollarIndexDate(), s.gold(), null, null, s.realRateAssessment(),
                s.dollarIndexAssessment(), s.researchVersion(), s.disclaimer(), s.input());
        var evidence = facts();
        for (int i = 3; i < 9; i++) evidence.set(i, new GoldEvidenceForecast.Fact(evidence.get(i).key(), null, MISSING));
        assertThatCode(() -> validator.validateEvidence(missing, result(evidence))).doesNotThrowAnyException();
        evidence.set(3, fact("rate1", "0", FLAT));
        assertThatThrownBy(() -> validator.validateEvidence(missing, result(evidence)))
                .isInstanceOf(UnsafeGoldForecastException.class).hasMessageContaining("rate1");
    }

    @Test
    @DisplayName("事实引用正确也不能绕过现有金融安全边界")
    void retainsSafety() {
        var bad = new GoldEvidenceForecast(new GoldDirectionForecastContent(ForecastDirection.NEUTRAL,
                "建议买入", List.of("原条件变化")), facts());
        assertThatThrownBy(() -> validator.validateEvidence(snapshot, bad))
                .isInstanceOf(UnsafeGoldForecastException.class);
    }

    private GoldEvidenceForecast result(List<GoldEvidenceForecast.Fact> evidence) {
        return new GoldEvidenceForecast(new GoldDirectionForecastContent(ForecastDirection.NEUTRAL,
                "研究候选，尚待方向验证", List.of("输入数据变化")), evidence);
    }

    // 手工核对的字面值，不从被测校验器反推预期。
    private ArrayList<GoldEvidenceForecast.Fact> facts() {
        return new ArrayList<>(List.of(fact("gold1", "0.1313", UP), fact("gold5", "3.8413", UP),
                fact("gold20", "11.7766", UP), fact("rate1", "5.0", UP), fact("rate5", "-1", DOWN),
                fact("rate20", "-3", DOWN), fact("usd1", "-0.1624", DOWN),
                fact("usd5", "-0.7065", DOWN), fact("usd20", "-2.1934", DOWN)));
    }

    private GoldEvidenceForecast.Fact fact(String key, String value, GoldEvidenceForecast.Trend trend) {
        return new GoldEvidenceForecast.Fact(key, new BigDecimal(value), trend);
    }
}
