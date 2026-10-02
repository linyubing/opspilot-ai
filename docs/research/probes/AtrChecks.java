package com.opspilot.ai.forecast.learning;

/** 量纲与输入隔离数学夹具，不代表行情或伪造市场数据。 */
public class AtrChecks {
    public static void main(String[] args) {
        near(AtrScale.ratio(20, 2000), 1);
        near(AtrScale.ratio(0, 2000), 0);
        near(AtrScale.ratio(2, 200), 1);
        near(AtrScale.ratio(200, 20000), 1);
        double[] raw = new double[20];
        for (int j = 0; j < 20; j++) raw[j] = j + 10;
        int atr = TrainingProbe.NAMES.indexOf("atr14"); raw[atr] = 20;
        double[] result = AtrScale.apply(raw, 2000);
        require(result != raw, "不能修改冻结原输入");
        near(raw[atr], 20); near(result[atr], 1);
        for (int j = 0; j < 20; j++) if (j != atr) near(result[j], raw[j]);
        for (double bad : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) rejects(() -> AtrScale.ratio(20, bad));
        for (double bad : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) rejects(() -> AtrScale.ratio(bad, 2000));
        rejects(() -> AtrScale.ratio(Double.MAX_VALUE, Double.MIN_VALUE));
        rejects(() -> AtrScale.apply(null, 2000)); rejects(() -> AtrScale.apply(new double[19], 2000));
        raw[1] = Double.NaN; rejects(() -> AtrScale.apply(raw, 2000));
        System.out.println("PASS: ATR已知比例/统一缩放不变/字段隔离/非法输入拒绝");
    }
    static void near(double a, double b) { require(Double.isFinite(a) && Math.abs(a - b) < 1e-12, a + " != " + b); }
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void rejects(Runnable action) {
        boolean rejected = false; try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "非法输入必须拒绝");
    }
}
