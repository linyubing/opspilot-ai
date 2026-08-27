package com.opspilot.ai.forecast;

/** 表示黄金方向预测等待验证、已验证或作废等生命周期状态。 */
public enum ForecastStatus { PENDING, RESOLVED, DATA_MISSING, VOIDED }
