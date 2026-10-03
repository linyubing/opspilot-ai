package com.opspilot.ai.forecast.learning;

import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

/** 对齐价格和VIX训练日期；原始价格保留，缺失VIX不补值。 */
final class VixCohort {
    record Row(LocalDate date, LocalDate target, String actual, double[] price, double[] risk) {}
    static List<Row> training(List<Row> rows, LocalDate start) {
        if(rows==null||start==null)throw new IllegalArgumentException("训练日期与输入不能为空");
        List<Row> result=new ArrayList<>();LocalDate previous=null;
        for(Row row:rows){
            validate(row);
            if(previous!=null&&!row.date().isAfter(previous))throw new IllegalArgumentException("输入必须唯一且按日期升序");
            previous=row.date();
            if(row.risk()!=null&&row.date().isBefore(start)&&row.target().isBefore(start))result.add(row);
        }
        return List.copyOf(result);
    }
    static double[] combine(Row row) {
        validate(row);
        if(row.risk()==null)throw new IllegalArgumentException("完整VIX特征缺失，不允许补值");
        double[] result=Arrays.copyOf(row.price(),24);
        System.arraycopy(row.risk(),0,result,20,4);return result;
    }
    private static void validate(Row row) {
        if(row==null||row.date()==null||row.target()==null||!row.target().isAfter(row.date())
                ||!row.target().isBefore(LocalDate.parse("2024-11-11"))
                ||!List.of("BULLISH","NEUTRAL","BEARISH").contains(row.actual()))
            throw new IllegalArgumentException("训练日期、目标或类别无效");
        finite(row.price(),20);if(row.risk()!=null)finite(row.risk(),4);
    }
    private static void finite(double[] x,int size){
        if(x==null||x.length!=size)throw new IllegalArgumentException("特征维度无效");
        for(double v:x)if(!Double.isFinite(v))throw new IllegalArgumentException("特征必须是有限数值");
    }
}
