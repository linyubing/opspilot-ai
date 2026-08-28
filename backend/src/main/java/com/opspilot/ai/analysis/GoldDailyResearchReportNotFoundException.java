package com.opspilot.ai.analysis;

/** 表示最新黄金研究快照尚未形成完整日报。 */
public class GoldDailyResearchReportNotFoundException
        extends RuntimeException {

    public GoldDailyResearchReportNotFoundException(String missingPart) {
        super("最新黄金研究快照尚未形成完整报告，缺少：" + missingPart);
    }
}
