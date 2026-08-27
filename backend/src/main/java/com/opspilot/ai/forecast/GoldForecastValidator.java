package com.opspilot.ai.forecast;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** 校验黄金方向预测的结构、长度和金融安全边界。 */
@Component
public class GoldForecastValidator {

    private static final Pattern NUMERIC_PROBABILITY = Pattern.compile(
            "(?:上涨|下跌|涨|跌).{0,8}\\d+(?:\\.\\d+)?%"
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
