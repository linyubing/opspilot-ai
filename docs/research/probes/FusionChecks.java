package com.opspilot.ai.forecast.learning;

import java.util.Arrays;

/** 手算概率与训练矩阵的行为检查；夹具不是黄金或宏观行情。 */
public class FusionChecks {
    public static void main(String[] args) {
        double[] price = {.7,.2,.1}, macro = {.1,.2,.7};
        near(FusionMix.mix(price, macro), new double[]{.4,.2,.4});
        near(price, new double[]{.7,.2,.1}); near(macro, new double[]{.1,.2,.7});
        for (double[] bad : new double[][]{{.1,.2}, {.1,.2,.8}, {Double.NaN,.2,.8}, {-.1,.2,.9}})
            rejects(() -> FusionMix.mix(price,bad));
        var scale = new FusionScale(new double[][]{{1,10},{3,10}});
        near(scale.mean,new double[]{2,10}); near(scale.std,new double[]{1,0});
        near(scale.values(new double[]{5,100}),new double[]{3,0});
        rejects(() -> new FusionScale(new double[][]{{Double.NaN,1}}));
        rejects(() -> scale.values(new double[]{1}));
        System.out.println("FUSION_MATH_PASS");
    }
    static void near(double[] actual, double[] want) {
        if (actual.length != want.length) throw new AssertionError("维数不一致");
        for (int i=0;i<actual.length;i++) if (!Double.isFinite(actual[i]) || Math.abs(actual[i]-want[i])>1e-12)
            throw new AssertionError(Arrays.toString(actual)+" != "+Arrays.toString(want));
    }
    static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("非法概率或矩阵应被拒绝");
    }
}
