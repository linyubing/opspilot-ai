package com.opspilot.ai.forecast.review;

/** 可评分的已结算信号不足时拒绝复盘，弃权不能凑样本。 */
public class InsufficientForecastReviewSamplesException
        extends RuntimeException {

    public InsufficientForecastReviewSamplesException(
            int requiredCount,
            int actualCount
    ) {
        super(
                "黄金预测复盘至少需要 "
                        + requiredCount
                        + " 条已结算评分信号，当前只有 "
                        + actualCount
                        + " 条"
        );
    }
}
