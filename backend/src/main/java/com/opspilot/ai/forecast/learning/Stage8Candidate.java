package com.opspilot.ai.forecast.learning;

/** 保存阶段8候选特征组合及判断依据。 */
public record Stage8Candidate(
        boolean passed,
        FeatureProfile profile,
        String reason
) {
}
