package com.opspilot.ai.forecast.review;

/** 表示已验证的预测样本不足，暂时不能进行可靠的 AI 复盘。 */
public class InsufficientForecastReviewSamplesException
        extends RuntimeException {

    public InsufficientForecastReviewSamplesException(
            int requiredCount,
            int actualCount
    ) {
        super(
                "黄金预测复盘至少需要 "
                        + requiredCount
                        + " 条已解析样本，当前只有 "
                        + actualCount
                        + " 条"
        );
    }
}
