package com.opspilot.ai.forecast.learning;

/** 离线等权概率融合；只接受完整合法的三方向概率，不归一化补值。 */
final class FusionMix {
    static double[] mix(double[] price, double[] macro) {
        validate(price); validate(macro);
        double[] result = new double[3];
        for (int c=0;c<3;c++) result[c] = (price[c]+macro[c])/2;
        return result;
    }
    private static void validate(double[] p) {
        if (p==null || p.length!=3) throw new IllegalArgumentException("需要完整三方向概率");
        double sum=0;
        for (double value:p) {
            if (!Double.isFinite(value) || value<0 || value>1) throw new IllegalArgumentException("概率必须是有限非负数");
            sum+=value;
        }
        if (Math.abs(sum-1)>1e-10) throw new IllegalArgumentException("概率总和必须为1");
    }
}
