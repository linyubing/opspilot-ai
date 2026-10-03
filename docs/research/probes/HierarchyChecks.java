package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import java.time.LocalDate;
import java.util.List;

/** 两阶段概率、日期和二分类数学检查；不是模拟黄金行情。 */
public class HierarchyChecks {
    public static void main(String[] args) {
        // 组合若丢掉任一头或错置类别，本例必失败。
        FusionChecks.near(HierarchyModel.combine(.8,.75),new double[]{.6,.2,.2});
        FusionChecks.near(HierarchyModel.combine(0,.3),new double[]{0,1,0});
        FusionChecks.near(HierarchyModel.combine(1,0),new double[]{0,0,1});
        FusionChecks.rejects(()->HierarchyModel.combine(Double.NaN,.5));
        FusionChecks.rejects(()->HierarchyModel.combine(.8,1.1));
        var a=row("2020-01-01","2020-01-02",ForecastDirection.BULLISH);
        var b=row("2020-01-02","2020-01-03",ForecastDirection.NEUTRAL);
        var c=row("2020-01-03","2020-01-04",ForecastDirection.BEARISH);
        var all=List.of(a,b,c);
        if(!HierarchyModel.settled(all,LocalDate.parse("2020-01-04")).equals(List.of(a,b)))throw new AssertionError("目标等于起点仍未结算");
        if(!HierarchyModel.directions(all).equals(List.of(a,c)))throw new AssertionError("方向头不能训练震荡标签");
        FusionChecks.rejects(()->HierarchyModel.settled(List.of(b,a),LocalDate.parse("2020-01-04")));
        double[][] x={{-2},{-1},{1},{2}};int[] y={0,0,1,1};double[] w={.3,-.2};
        double[] g=BinaryFit.gradient(x,y,w,.01);
        for(int j=0;j<w.length;j++){double[] plus=w.clone(),minus=w.clone();plus[j]+=1e-5;minus[j]-=1e-5;
            near(g[j],(BinaryFit.loss(x,y,plus,.01)-BinaryFit.loss(x,y,minus,.01))/2e-5,1e-8);}
        near(BinaryFit.loss(x,y,new double[]{0,0},.01),Math.log(2),1e-12);
        near(BinaryFit.loss(x,y,new double[]{0,.4},.01),BinaryFit.loss(x,y,new double[]{0,.4},1),1e-12);
        var h=BinaryFit.hessian(x,y,new double[]{0,0},.01);
        near(h[0][0],.63,1e-12);near(h[0][1],0,1e-12);near(h[1][1],.25,1e-12);
        var fit=BinaryFit.fit(x,y,.01);var repeat=BinaryFit.fit(x,y,.01);
        if(!fit.converged()||fit.gradient()>1e-6||BinaryFit.probability(fit.weights(),new double[]{2})<=.5)throw new AssertionError("必须实际学习并收敛");
        FusionChecks.near(fit.weights(),repeat.weights());
        FusionChecks.rejects(()->BinaryFit.fit(x,new int[]{0,0,0,0},.01));
        FusionChecks.rejects(()->BinaryFit.probability(new double[]{1,2},new double[]{Double.NaN}));
        System.out.println("HIERARCHY_MATH_PASS");
    }
    static TrainingProbe.Row row(String date,String target,ForecastDirection label){return new TrainingProbe.Row(LocalDate.parse(date),LocalDate.parse(target),new double[20],label);}
    static void near(double a,double b,double tolerance){if(!Double.isFinite(a)||Math.abs(a-b)>tolerance)throw new AssertionError(a+" != "+b);}
}
