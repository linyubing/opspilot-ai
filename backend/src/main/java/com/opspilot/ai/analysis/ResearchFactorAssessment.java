package com.opspilot.ai.analysis;

/**
 * 保存单因子状态、规则版本和可读解释。
 */
public record ResearchFactorAssessment(
        GoldFactorStatus status,
        String ruleVersion,
        String explanation
) {
}
