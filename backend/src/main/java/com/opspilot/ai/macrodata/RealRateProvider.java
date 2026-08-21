package com.opspilot.ai.macrodata;

public interface RealRateProvider {

    RealRateBatch fetchDailyObservations();
}
