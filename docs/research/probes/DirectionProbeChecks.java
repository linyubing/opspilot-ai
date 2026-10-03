package com.opspilot.ai.forecast.learning;

/** 手算边界与排序数学夹具，不是市场行情或模拟预测。 */
public class DirectionProbeChecks {
    public static void main(String[] args) {
        if(!DirectionProbe.outcome("100","100.500001").equals(new DirectionProbe.Outcome("BULLISH","EDGE_0_5_0_6")))
            throw new AssertionError("严格超出0.5%必须判上涨");
        if(!DirectionProbe.outcome("100","99.5").equals(new DirectionProbe.Outcome("NEUTRAL","WITHIN_0_5")))throw new AssertionError("0.5%边界归中性");
        if(!DirectionProbe.outcome("100","98").equals(new DirectionProbe.Outcome("BEARISH","LARGE_1_2")))throw new AssertionError("2%边界含在大幅组");
        check(DirectionProbe.auc(new int[]{0,2},new double[]{.8,.2},0),1);
        check(DirectionProbe.auc(new int[]{0,2},new double[]{.2,.8},0),0);
        check(DirectionProbe.auc(new int[]{0,2},new double[]{.5,.5},0),.5);
        check(DirectionProbe.auc(new int[]{0,0,2,2},new double[]{.9,.5,.5,.1},0),.875);
        if(DirectionProbe.auc(new int[]{0},new double[]{.9},0)!=null)throw new AssertionError("缺类必须null");
        FusionChecks.rejects(()->DirectionProbe.outcome("0","1"));
        FusionChecks.rejects(()->DirectionProbe.auc(new int[]{0,2},new double[]{Double.NaN,.1},0));
        System.out.println("DIRECTION_MATH_PASS");
    }
    static void check(Double actual,double expected){if(actual==null||Math.abs(actual-expected)>1e-12)throw new AssertionError(actual+" != "+expected);}
}
