package com.opspilot.ai.macrodata;

/** 表示当前没有可查询的广义美元指数观测。 */
public class DollarIndexNotFoundException extends RuntimeException {

    public DollarIndexNotFoundException(String message) {
        super(message);
    }
}
