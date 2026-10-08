package com.opspilot.ai.forecast.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.ai.macrodata.FredHistoryStore;
import java.time.LocalDate;

/** 使用真实历史归档检查利率计算与拒绝分支，不生成行情或预测样例。 */
public class HourlyRateChecks {
    public static void main(String[] args) {
        var batch=new FredHistoryStore(new ObjectMapper(),args[0]).load();
        var day=LocalDate.parse("2026-06-25");
        var rows=batch.recent("DFII10",day,6);
        var values=HourlyBench.rateValues(day,rows);
        near(values[0],2.29,"真实版本利率水平");
        near(values[1],.14,"真实版本5个观测间隔变化");
        reject(()->HourlyBench.rateValues(day,rows.subList(0,5)),"缺少观测");
        reject(()->HourlyBench.rateValues(rows.getFirst().observationDate(),rows),"未来或当日观测");
        reject(()->HourlyBench.rateValues(day.plusDays(10),rows),"超期观测");
        var reversed=new java.util.ArrayList<>(rows); java.util.Collections.reverse(reversed);
        reject(()->HourlyBench.rateValues(day,reversed),"观测顺序错误");
        System.out.println("real-rate value and boundary checks: 6 passed");
    }
    private static void reject(Runnable call,String name) {
        try { call.run(); } catch(IllegalArgumentException expected) { return; }
        throw new AssertionError(name+"未被拒绝");
    }
    private static void near(double actual,double expected,String name) {
        if(Math.abs(actual-expected)>1e-12) throw new AssertionError(name+"不符");
    }
}
