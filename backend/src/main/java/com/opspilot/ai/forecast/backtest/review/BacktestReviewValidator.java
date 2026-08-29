package com.opspilot.ai.forecast.backtest.review;

import org.springframework.stereotype.Component;

import java.util.Set;

/** 校验 AI 回测复盘的字段完整性和证据编号边界。 */
@Component
public class BacktestReviewValidator {

    public void validate(
            BacktestReviewContent content,
            Set<String> evidenceIds
    ) {
        if (content == null || evidenceIds == null || evidenceIds.isEmpty()) {
            fail("回测复盘内容或证据范围为空");
        }
        text(content.summary(), 2000, "复盘摘要");
        evidence(content.summaryEvidence(), evidenceIds, "摘要证据");

        if (content.patterns() == null
                || content.patterns().isEmpty()
                || content.patterns().size() > 10) {
            fail("错误模式数量必须在 1 到 10 之间");
        }
        for (BacktestErrorPattern pattern : content.patterns()) {
            if (pattern == null) {
                fail("错误模式不能为空");
            }
            text(pattern.category(), 100, "错误类型");
            text(pattern.observation(), 1000, "错误观察");
            text(pattern.improvement(), 1000, "改进假设");
            text(pattern.validationMethod(), 1000, "验证方法");
            evidence(pattern.evidence(), evidenceIds, "错误模式证据");
        }

        if (content.risks() == null || content.risks().size() > 10) {
            fail("风险列表不能缺失且最多 10 条");
        }
        for (BacktestReviewRisk risk : content.risks()) {
            if (risk == null) {
                fail("风险说明不能为空");
            }
            text(risk.description(), 500, "风险说明");
            evidence(risk.evidence(), evidenceIds, "风险证据");
        }
        text(content.disclaimer(), 500, "免责声明");
    }

    private void evidence(
            java.util.List<String> values,
            Set<String> allowed,
            String name
    ) {
        if (values == null || values.isEmpty() || values.size() > 20) {
            fail(name + "不能为空且最多 20 条");
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                fail(name + "不能包含空编号");
            }
            if (!allowed.contains(value)) {
                fail(name + "包含当前回测之外的编号");
            }
        }
    }

    private void text(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max) {
            fail(name + "不能为空且长度不能超过 " + max);
        }
    }

    private void fail(String message) {
        throw new InvalidBacktestReviewAiResponseException(
                message,
                new IllegalArgumentException(message)
        );
    }
}
