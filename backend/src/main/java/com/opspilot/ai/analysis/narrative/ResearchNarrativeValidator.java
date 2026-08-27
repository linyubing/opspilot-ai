package com.opspilot.ai.analysis.narrative;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/** 校验大模型研究解读的结构完整性、长度限制和金融安全边界。 */
@Component
public class ResearchNarrativeValidator {

    private static final int SUMMARY_MAX_LENGTH = 500;
    private static final int ANALYSIS_MAX_LENGTH = 2000;
    private static final int LIST_MAX_SIZE = 5;
    private static final int LIST_ITEM_MAX_LENGTH = 300;

    /**
     * 匹配“上涨概率 70%”等数值化预测表达。
     * 中间允许出现少量“概率为”等连接文字。
     */
    private static final Pattern NUMERIC_PROBABILITY = Pattern.compile(
            "(?:上涨|下跌|涨|跌).{0,8}\\d+(?:\\.\\d+)?%"
    );

    /**
     * 只拦截明确的行动指令，避免误伤“不构成投资建议”等免责声明。
     */
    private static final List<String> FORBIDDEN_PHRASES = List.of(
            "建议买入",
            "建议卖出",
            "目标价",
            "止损位"
    );

    public void validate(ResearchNarrativeContent content) {
        if (content == null) {
            throw unsafe("研究解读不能为空");
        }

        validateText("摘要", content.summary(), SUMMARY_MAX_LENGTH);

        validateText(
                "实际利率分析",
                content.realRateAnalysis(),
                ANALYSIS_MAX_LENGTH
        );
        validateText(
                "美元指数分析",
                content.dollarIndexAnalysis(),
                ANALYSIS_MAX_LENGTH
        );
        validateText(
                "免责声明",
                content.disclaimer(),
                SUMMARY_MAX_LENGTH
        );

        validateList("风险列表", content.risks());
        validateList("观察列表", content.watchList());

        String completeText = String.join(
                "\n",
                content.summary(),
                content.realRateAnalysis(),
                content.dollarIndexAnalysis(),
                String.join("\n", content.risks()),
                String.join("\n", content.watchList()),
                content.disclaimer()
        );

        validateFinancialBoundary(completeText);
        validateDisclaimer(content.disclaimer());
    }

    /** 校验必填文本及长度上限。 */
    private void validateText(String fieldName, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw unsafe(fieldName + "不能为空");
        }

        if (value.length() > maxLength) {
            throw unsafe(fieldName + "长度不能超过 " + maxLength + " 个字符");
        }
    }

    /** 校验列表数量以及每个列表项的内容。 */
    private void validateList(
            String fieldName,
            List<String> values
    ) {
        if (values == null || values.isEmpty()) {
            throw unsafe(fieldName + "不能为空");
        }

        if (values.size() > LIST_MAX_SIZE) {
            throw unsafe(
                    fieldName + "不能超过 " + LIST_MAX_SIZE + " 项"
            );
        }

        for (String value : values) {
            validateText(
                    fieldName + "单项",
                    value,
                    LIST_ITEM_MAX_LENGTH
            );
        }
    }

    /** 拒绝交易指令、目标价格和数值化涨跌概率。 */
    private void validateFinancialBoundary(String completeText) {
        for (String phrase : FORBIDDEN_PHRASES) {
            if (completeText.contains(phrase)) {
                throw unsafe("研究解读包含禁止内容：" + phrase);
            }
        }

        if (NUMERIC_PROBABILITY.matcher(completeText).find()) {
            throw unsafe("研究解读不得给出数值化涨跌概率");
        }
    }

    /** 免责声明必须明确覆盖三个产品边界。 */
    private void validateDisclaimer(String disclaimer) {
        boolean complete = disclaimer.contains("不构成")
                && disclaimer.contains("价格预测")
                && disclaimer.contains("交易")
                && disclaimer.contains("投资建议");

        if (!complete) {
            throw unsafe(
                    "免责声明必须明确说明不构成价格预测、交易或投资建议"
            );
        }
    }

    private UnsafeResearchNarrativeException unsafe(String message) {
        return new UnsafeResearchNarrativeException(message);
    }
}
