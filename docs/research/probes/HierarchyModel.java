package com.opspilot.ai.forecast.learning;

import java.time.LocalDate;
import java.util.List;

/** 先波动后方向的离线三分类结构；全部日期均输出概率。 */
final class HierarchyModel {
    static double[] combine(double move,double up){
        if(!Double.isFinite(move)||!Double.isFinite(up)||move<0||move>1||up<0||up>1)throw new IllegalArgumentException("两头概率必须合法");
        return new double[]{move*up,1-move,move*(1-up)};
    }
    static List<TrainingProbe.Row> settled(List<TrainingProbe.Row> rows,LocalDate start){return HistorySlice.training(rows,start);}
    static List<TrainingProbe.Row> directions(List<TrainingProbe.Row> rows){return rows.stream().filter(r->r.label()!=com.opspilot.ai.forecast.ForecastDirection.NEUTRAL).toList();}
}
