package com.opspilot.ai.forecast.learning;

/** 离线ATR比例变换；分母只能用预测基准日的真实收盘价。 */
final class AtrScale {
    static double ratio(double atr, double close) {
        if (!Double.isFinite(atr) || atr < 0 || !Double.isFinite(close) || close <= 0)
            throw new IllegalArgumentException("ATR须为有限非负值，基准收盘须为有限正数");
        double value = atr / close * 100;
        if (!Double.isFinite(value)) throw new IllegalArgumentException("ATR比例超出有限范围");
        return value;
    }
    static double[] apply(double[] x, double close) {
        if (x == null || x.length != 20) throw new IllegalArgumentException("必须提供完整20维真实特征");
        for (double value : x) if (!Double.isFinite(value)) throw new IllegalArgumentException("特征必须为有限数");
        double[] result = x.clone();
        int index = TrainingProbe.NAMES.indexOf("atr14");
        result[index] = ratio(x[index], close);
        return result;
    }
}
