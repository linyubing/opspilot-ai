package com.opspilot.ai.forecast;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.math.BigDecimal;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** 校验黄金方向预测的结构、长度和金融安全边界。 */
@Component
public class GoldForecastValidator {

    /** 研究候选入口；正式生成暂不调用。 */
    public void validateEvidence(com.opspilot.ai.analysis.GoldResearchSnapshot snapshot,
            GoldEvidenceForecast content) {
        if (snapshot == null || snapshot.gold() == null || content == null || content.evidence() == null) {
            throw unsafe("研究候选缺少快照或事实引用");
        }
        validate(content.forecast());
        var expected = new LinkedHashMap<String, BigDecimal>();
        expected.put("gold1", snapshot.gold().return1());
        expected.put("gold5", snapshot.gold().return5());
        expected.put("gold20", snapshot.gold().return20());
        var rate = snapshot.realRate();
        expected.put("rate1", rate == null ? null : rate.basisPointChange1());
        expected.put("rate5", rate == null ? null : rate.basisPointChange5());
        expected.put("rate20", rate == null ? null : rate.basisPointChange20());
        var dollar = snapshot.dollarIndex();
        expected.put("usd1", dollar == null ? null : dollar.return1());
        expected.put("usd5", dollar == null ? null : dollar.return5());
        expected.put("usd20", dollar == null ? null : dollar.return20());
        if (content.evidence().size() != expected.size()) throw unsafe("事实引用必须覆盖全部九项变化");
        var seen = new HashSet<String>();
        for (var fact : content.evidence()) {
            if (fact == null || !expected.containsKey(fact.key()) || !seen.add(fact.key())) {
                throw unsafe("事实引用含空项、未知字段或重复字段");
            }
            var value = expected.get(fact.key());
            // compareTo忽略尾零精度差异，但不能接受舍入后相近或补造的值。
            if (value == null ? fact.value() != null
                    : fact.value() == null || value.compareTo(fact.value()) != 0) {
                throw unsafe("事实引用数值与快照不一致：" + fact.key());
            }
            if (fact.trend() != trend(value)) throw unsafe("事实引用变化方向与数值符号不一致：" + fact.key());
        }
    }

    private GoldEvidenceForecast.Trend trend(BigDecimal value) {
        if (value == null) return GoldEvidenceForecast.Trend.MISSING;
        return value.signum() > 0 ? GoldEvidenceForecast.Trend.UP
                : value.signum() < 0 ? GoldEvidenceForecast.Trend.DOWN : GoldEvidenceForecast.Trend.FLAT;
    }

    private static final Pattern NUMERIC_PROBABILITY = Pattern.compile(
            "(?:(?:上涨|下跌|涨|跌).{0,8}(?:概率|可能性|胜率).{0,8}"
                    + "\\d+(?:\\.\\d+)?%|\\d+(?:\\.\\d+)?%.{0,8}"
                    + "(?:概率|可能性|胜率).{0,8}(?:上涨|下跌|涨|跌))"
    );
    private static final List<String> FORBIDDEN_PHRASES = List.of(
            "建议买入", "建议卖出", "目标价", "止损位", "仓位"
    );

    public void validate(GoldDirectionForecastContent content) {
        if (content == null) {
            throw unsafe("黄金方向预测不能为空");
        }
        if (content.direction() == null) {
            throw unsafe("预测方向不能为空");
        }
        validateText("研究依据", content.reasoning(), 2000);
        validateConditions(content.invalidationConditions());

        String completeText = content.reasoning() + "\n"
                + String.join("\n", content.invalidationConditions());
        for (String phrase : FORBIDDEN_PHRASES) {
            if (completeText.contains(phrase)) {
                throw unsafe("黄金方向预测包含禁止内容：" + phrase);
            }
        }
        if (NUMERIC_PROBABILITY.matcher(completeText).find()) {
            throw unsafe("黄金方向预测不得给出数值化涨跌概率");
        }
    }

    private void validateConditions(List<String> conditions) {
        if (conditions == null || conditions.isEmpty() || conditions.size() > 5) {
            throw unsafe("失效条件必须包含 1 到 5 项");
        }
        conditions.forEach(value -> validateText("失效条件", value, 300));
    }

    private void validateText(String name, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw unsafe(name + "不能为空");
        }
        if (value.strip().length() > maxLength) {
            throw unsafe(name + "长度不能超过 " + maxLength + " 个字符");
        }
    }

    private UnsafeGoldForecastException unsafe(String message) {
        return new UnsafeGoldForecastException(message);
    }
}
