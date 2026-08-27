package com.opspilot.ai.forecast;

/** 表示研究快照版本不满足黄金方向预测的输入要求。 */
public class InvalidGoldForecastSnapshotException extends RuntimeException {

    public InvalidGoldForecastSnapshotException(String researchVersion) {
        super("只有正式双因子快照可以生成方向预测，当前版本：" + researchVersion);
    }
}
