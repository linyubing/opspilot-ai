package com.opspilot.ai.analysis;

public record ResearchFactorAssessment(
        RealRateFactorStatus status,
        String ruleVersion,
        String explanation
) {
}
