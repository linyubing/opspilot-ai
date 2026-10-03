package com.opspilot.ai.forecast.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;

/** 独立Java复核十进制实际收益幅度与概率排序，不训练模型。 */
public class DirectionProbe {
    record Outcome(String label,String group) {}
    static final List<String> LABELS=List.of("BULLISH","NEUTRAL","BEARISH");
    static final List<String> KEYS=List.of("candidate","price","macro","frequencyMix","prior","pitReference");
    static final List<String> GROUPS=List.of("WITHIN_0_5","EDGE_0_5_0_6","MID_0_6_1","LARGE_1_2","OVER_2");
    record Row(String date,String target,String actual,String group,Map<String,double[]> p) {}
    static Outcome outcome(String base,String target) {
        BigDecimal a=new BigDecimal(base),b=new BigDecimal(target);
        if(a.signum()<=0||b.signum()<=0)throw new IllegalArgumentException("真实收盘必须大于零");
        BigDecimal move=b.subtract(a).multiply(BigDecimal.valueOf(100)),abs=move.abs();
        String label=abs.compareTo(a.multiply(new BigDecimal("0.5")))<=0?"NEUTRAL":move.signum()>0?"BULLISH":"BEARISH";
        String group=abs.compareTo(a.multiply(new BigDecimal("0.5")))<=0?GROUPS.get(0):
                abs.compareTo(a.multiply(new BigDecimal("0.6")))<=0?GROUPS.get(1):
                abs.compareTo(a)<=0?GROUPS.get(2):abs.compareTo(a.multiply(BigDecimal.valueOf(2)))<=0?GROUPS.get(3):GROUPS.get(4);
        return new Outcome(label,group);
    }
    static Double auc(int[] labels,double[] scores,int positive) {
        if(labels.length!=scores.length)throw new IllegalArgumentException("概率与标签数量不符");
        long yes=0,no=0;double wins=0;
        for(int i=0;i<labels.length;i++) {
            if(labels[i]<0||labels[i]>2||!Double.isFinite(scores[i])||scores[i]<0||scores[i]>1)throw new IllegalArgumentException("排序输入非法");
            if(labels[i]==positive)yes++;else no++;
        }
        if(yes==0||no==0)return null;
        for(int i=0;i<labels.length;i++)if(labels[i]==positive)for(int j=0;j<labels.length;j++)if(labels[j]!=positive)
            wins+=scores[i]>scores[j]?1:scores[i]==scores[j]?.5:0;
        return wins/(yes*no);
    }
    static Map<String,Object> models(List<Row> rows) {
        Map<String,Object> result=new LinkedHashMap<>();int[] actual=rows.stream().mapToInt(r->LABELS.indexOf(r.actual())).toArray();
        for(String key:KEYS) {
            long[][] matrix=new long[3][3];int[] predictedCounts=new int[3];int correct=0;
            double brier=0,loss=0;
            for(int i=0;i<rows.size();i++) {
                double[] p=rows.get(i).p().get(key);FusionMix.mix(p,p);int best=0;
                for(int c=1;c<3;c++)if(p[c]>p[best])best=c;matrix[actual[i]][best]++;predictedCounts[best]++;if(best==actual[i])correct++;
                for(int c=0;c<3;c++)brier+=Math.pow(p[c]-(c==actual[i]?1:0),2);loss-=Math.log(Math.max(p[actual[i]],1e-15));
            }
            List<Double> aucs=new ArrayList<>();for(int c=0;c<3;c++){final int id=c;aucs.add(auc(actual,rows.stream().mapToDouble(r->r.p().get(key)[id]).toArray(),c));}
            var direction=rows.stream().filter(r->!r.actual().equals("NEUTRAL")).toList();
            Double directional=auc(direction.stream().mapToInt(r->LABELS.indexOf(r.actual())).toArray(),direction.stream().mapToDouble(r->{
                double[] p=r.p().get(key);if(p[0]+p[2]<=0)throw new IllegalArgumentException("条件方向概率不可定义");return p[0]/(p[0]+p[2]);
            }).toArray(),0);
            Map<String,Object> model=new LinkedHashMap<>();model.put("count",rows.size());model.put("correct",correct);model.put("matrix",matrix);
            model.put("brierScore",rows.isEmpty()?null:brier/rows.size());model.put("logLoss",rows.isEmpty()?null:loss/rows.size());
            model.put("predictedCounts",predictedCounts);model.put("oneVsRestAuc",aucs);model.put("directionalAuc",directional);result.put(key,model);
        }
        return result;
    }
    public static void main(String[] args)throws Exception {
        DirectionProbeChecks.main(new String[0]);var json=new ObjectMapper();
        JsonNode tree=json.readTree(Path.of("../docs/research/2026-10-03-tree-check.json").toFile());
        JsonNode fusion=json.readTree(Path.of("../docs/research/2026-10-03-fusion-check.json").toFile());
        List<JsonNode> bars=new ArrayList<>();tree.path("bars").forEach(bars::add);Map<String,Integer> dates=new HashMap<>();
        for(int i=0;i<bars.size();i++)if(dates.put(bars.get(i).path("date").asText(),i)!=null)throw new IllegalStateException("日线日期重复");
        List<Row> all=new ArrayList<>();List<Map<String,Object>> folds=new ArrayList<>();
        for(JsonNode fold:fusion.path("folds")) {
            List<Row> rows=new ArrayList<>();for(JsonNode saved:fold.path("predictions")) {
                String day=saved.path("date").asText(),target=saved.path("target").asText();Integer index=dates.get(day);
                if(index==null||index+1>=bars.size()||!bars.get(index+1).path("date").asText().equals(target)||target.compareTo("2024-11-11")>=0)
                    throw new IllegalStateException("结算目标不是受限下一根真实日线");
                var settled=outcome(bars.get(index).path("close").asText(),bars.get(index+1).path("close").asText());
                if(!settled.label().equals(saved.path("actual").asText()))throw new IllegalStateException("真实标签不符");
                Map<String,double[]> probabilities=new LinkedHashMap<>();for(String key:KEYS)probabilities.put(key,json.convertValue(saved.path(key),double[].class));
                rows.add(new Row(day,target,settled.label(),settled.group(),probabilities));
            }
            if(rows.size()!=240)throw new IllegalStateException("验证数量不符");all.addAll(rows);
            Map<String,Object> summary=new LinkedHashMap<>();summary.put("start",fold.path("start").asText());summary.put("models",models(rows));folds.add(summary);
        }
        if(all.size()!=720)throw new IllegalStateException("全部验证数量不符");
        List<Map<String,Object>> groups=new ArrayList<>();for(String group:GROUPS){var rows=all.stream().filter(r->r.group().equals(group)).toList();
            groups.add(Map.of("group",group,"count",rows.size(),"models",models(rows)));}
        Map<String,Object> result=new LinkedHashMap<>();result.put("count",all.size());result.put("all",models(all));result.put("groups",groups);result.put("folds",folds);
        System.out.println("JAVA_DIRECTION_RESULT="+json.writeValueAsString(result));
    }
}
