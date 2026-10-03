package com.opspilot.ai.forecast.learning;

import java.time.LocalDate;
import java.util.*;

/** 日期与拼接数学夹具，不作为行情、训练样本或评分输入。 */
public final class VixCohortChecks {
    static VixCohort.Row row(String day, String target, double[] risk) {
        double[] price=new double[20];Arrays.fill(price,7);
        return new VixCohort.Row(LocalDate.parse(day),LocalDate.parse(target),"NEUTRAL",price,risk);
    }
    public static void main(String[] args) {
        double[] risk={10,20,30,40};
        var a=row("2024-01-01","2024-01-02",null);
        var b=row("2024-01-02","2024-01-03",risk);
        var c=row("2024-01-03","2024-01-04",risk);
        var d=row("2024-01-04","2024-01-05",risk);
        if(!VixCohort.training(List.of(a,b,c,d),LocalDate.parse("2024-01-04")).equals(List.of(b)))
            throw new AssertionError("训练混入缺失、未结算或验证样本");
        double[] x=VixCohort.combine(b);
        if(x.length!=24)throw new AssertionError("拼接维度错误");
        for(int i=0;i<20;i++)if(x[i]!=7)throw new AssertionError("价格特征被修改");
        for(int i=0;i<4;i++)if(x[i+20]!=risk[i])throw new AssertionError("VIX拼接顺序错误");
        x[20]=999;if(b.risk()[0]!=10)throw new AssertionError("拼接修改原始数组");
        rejects(()->VixCohort.combine(a));
        rejects(()->VixCohort.training(List.of(b,b),LocalDate.parse("2024-01-04")));
        rejects(()->VixCohort.training(List.of(c,b),LocalDate.parse("2024-01-04")));
        rejects(()->VixCohort.combine(row("2024-01-01","2024-01-01",risk)));
        rejects(()->VixCohort.combine(row("2024-01-01","2024-01-02",new double[]{1,2,3})));
        rejects(()->VixCohort.combine(row("2024-01-01","2024-01-02",new double[]{1,2,3,Double.NaN})));
        System.out.println("VIX_COHORT_CHECKS=8 PASS");
    }
    private static void rejects(Runnable action) {
        try{action.run();}catch(IllegalArgumentException expected){return;}
        throw new AssertionError("未拒绝无效训练输入");
    }
}
