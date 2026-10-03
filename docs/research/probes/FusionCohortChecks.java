package com.opspilot.ai.forecast.learning;

import java.time.LocalDate;
import java.util.List;

/** 仅用日期数学夹具测试结算隔离，不生成任何市场价格或预测结果。 */
public class FusionCohortChecks {
    public static void main(String[] args) {
        var start=LocalDate.parse("2020-01-03");
        var settled=new FusionCohort.Input(start.minusDays(2),start.minusDays(1),"NEUTRAL",new double[9]);
        var pending=new FusionCohort.Input(start.minusDays(1),start,"NEUTRAL",new double[9]);
        var future=new FusionCohort.Input(start,start.plusDays(1),"NEUTRAL",new double[9]);
        if (!FusionCohort.training(List.of(settled,pending,future),start).equals(List.of(settled)))
            throw new AssertionError("目标在预测日及以后的样本不能用于训练");
        FusionChecks.rejects(() -> FusionCohort.training(List.of(settled,settled),start));
        FusionChecks.rejects(() -> FusionCohort.training(List.of(pending,settled),start));
        System.out.println("FUSION_COHORT_PASS");
    }
}
