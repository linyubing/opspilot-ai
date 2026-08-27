package com.opspilot.ai.analysis.narrative;

/** 表示模型解读违反结构完整性或金融安全边界。 */
public class UnsafeResearchNarrativeException extends RuntimeException {

    public UnsafeResearchNarrativeException(String message) {
        super(message);
    }
}
