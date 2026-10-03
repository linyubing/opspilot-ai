package com.opspilot.ai.forecast.learning;

import java.time.LocalDate;
import java.util.List;

/** 宏观分支只取预测前已结算的真实样本，不裁短价格分支历史。 */
final class FusionCohort {
    record Input(LocalDate date, LocalDate target, String actual, double[] x) {}
    static List<Input> training(List<Input> rows, LocalDate start) {
        LocalDate previous=null;
        for (var row:rows) {
            if (row==null || row.date()==null || row.target()==null || !row.date().isBefore(row.target())
                    || (previous!=null && !previous.isBefore(row.date())))
                throw new IllegalArgumentException("样本必须按唯一日期升序并有未来结算日");
            previous=row.date();
        }
        return rows.stream().filter(r->r.date().isBefore(start)&&r.target().isBefore(start)).toList();
    }
}
