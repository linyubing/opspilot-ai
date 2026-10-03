package com.opspilot.ai.forecast;

/** 表示研究快照版本或窗口依据不满足正式预测要求。 */
public class InvalidGoldForecastSnapshotException extends RuntimeException {

    public InvalidGoldForecastSnapshotException(String researchVersion) {
        super("只有正式双因子快照可以生成方向预测，当前版本：" + researchVersion);
    }

    public InvalidGoldForecastSnapshotException(String researchVersion, String reason) {
        super(reason + "，当前版本：" + researchVersion);
    }
}
