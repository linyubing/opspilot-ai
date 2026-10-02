package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import java.time.LocalDate;
import java.util.List;

/** 时间隔离数学夹具，不代表任何实际行情。 */
public class HistoryChecks {
    static TrainingProbe.Row row(int day,int target,double value){double[] x=new double[20];x[0]=value;return new TrainingProbe.Row(
            LocalDate.of(2020,1,day),LocalDate.of(2020,1,target),x,ForecastDirection.NEUTRAL);}
    public static void main(String[] args){cutoff();invalid();System.out.println("PASS: 训练标签结算隔离/训练内标准化/维数与日期拒绝");}
    static void cutoff(){
        var rows=List.of(row(1,2,1),row(2,3,3),row(3,4,10000),row(4,5,20000));
        var train=HistorySlice.training(rows,LocalDate.of(2020,1,4));require(train.size()==2,"未排除目标日等于验证起点的未结算标签");
        var scale=new TrainingProbe.Scale(train);near(scale.mean[0],2);near(scale.std[0],1);near(scale.values(row(4,5,4))[0],2);
        for(int i=1;i<20;i++){near(scale.mean[i],0);near(scale.std[i],0);near(scale.values(row(4,5,4))[i],0);}
    }
    static void invalid(){
        rejects(()->HistorySlice.training(List.of(row(2,3,1),row(1,2,2)),LocalDate.of(2020,1,4)));
        rejects(()->HistorySlice.training(List.of(row(1,2,1),row(1,3,2)),LocalDate.of(2020,1,4)));
        rejects(()->HistorySlice.training(List.of(row(1,1,1)),LocalDate.of(2020,1,4)));
        rejects(()->HistorySlice.training(List.of(new TrainingProbe.Row(LocalDate.of(2020,1,1),LocalDate.of(2020,1,2),new double[19],ForecastDirection.NEUTRAL)),LocalDate.of(2020,1,4)));
        rejects(()->HistorySlice.training(List.of(row(1,2,Double.NaN)),LocalDate.of(2020,1,4)));
        rejects(()->HistorySlice.training(List.of(),LocalDate.of(2020,1,4)));
        rejects(()->HistorySlice.training(List.of(row(4,5,1)),LocalDate.of(2020,1,4)));
    }
    static void rejects(Runnable r){boolean rejected=false;try{r.run();}catch(IllegalArgumentException expected){rejected=true;}require(rejected,"非法训练输入必须拒绝");}
    static void near(double a,double b){require(Double.isFinite(a)&&Math.abs(a-b)<1e-12,a+" != "+b);}
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
