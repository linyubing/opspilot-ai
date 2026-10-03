package com.opspilot.ai.macrodata;

/** 纯数学夹具，不代表真实行情；检查公式、缺失和数值边界。 */
public final class VixFeatureChecks {
    public static void main(String[] args) {
        double[] v = new double[21];
        java.util.Arrays.fill(v, 10);
        v[0] = 20; v[1] = 16; v[5] = 25; v[20] = 40;
        check(VixFeatures.compute(v), new double[]{20, 25, -20, -50});
        java.util.Arrays.fill(v, 15);
        check(VixFeatures.compute(v), new double[]{15, 0, 0, 0});
        rejects(null); rejects(new double[20]); rejects(new double[22]);
        for (double bad : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            v[7] = bad; rejects(v); v[7] = 15;
        }
        v[0] = Double.MAX_VALUE; v[1] = Double.MIN_VALUE; rejects(v);
        System.out.println("VIX_FEATURE_CHECKS=10 PASS");
    }
    private static void check(double[] actual, double[] expected) {
        if (actual.length != expected.length) throw new AssertionError("特征维度错误");
        for (int i = 0; i < expected.length; i++)
            if (!Double.isFinite(actual[i]) || Math.abs(actual[i] - expected[i]) > 1e-10)
                throw new AssertionError("VIX特征公式错误，位置=" + i);
    }
    private static void rejects(double[] values) {
        try { VixFeatures.compute(values); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("未拒绝不完整或非法VIX窗口");
    }
}
