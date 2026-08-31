package com.opspilot.ai.forecast.learning;

/** 定义黄金预测需要结算的交易日周期。 */
public enum ForecastHorizon {
    NEXT_DAY(1),
    FIVE_DAYS(5),
    TWENTY_DAYS(20);

    private final int sessions;

    ForecastHorizon(int sessions) {
        this.sessions = sessions;
    }

    public int sessions() {
        return sessions;
    }
}
