package com.opspilot.ai.forecast.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opspilot.ai.forecast.ForecastDirection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/** 固定两阶段结构的真实离线实验；不读新行情、不写数据库、不晋级。 */
public class HierarchyRun {
    static final Path TREE=Path.of("../docs/research/2026-10-03-tree-check.json");
    static final double LAMBDA=.01;
    record Head(List<LocalDate> dates,double[] mean,double[] std,BinaryFit.Fit fit,double repeatDifference) {}
    record Prediction(String date,String target,String actual,double move,double side,double[] candidate,double[] price,double[] pitReference,double[] prior) {}
    record Fold(String start,String end,Head move,Head side,List<Prediction> predictions,double repeatDifference) {}
    static Head train(List<TrainingProbe.Row> rows,boolean direction) {
        var scale=new FusionScale(rows.stream().map(TrainingProbe.Row::x).toArray(double[][]::new));
        double[][] x=rows.stream().map(r->scale.values(r.x())).toArray(double[][]::new);
        int[] y=rows.stream().mapToInt(r->direction?(r.label()==ForecastDirection.BULLISH?1:0):(r.label()==ForecastDirection.NEUTRAL?0:1)).toArray();
        var fit=BinaryFit.fit(x,y,LAMBDA);var repeat=BinaryFit.fit(x,y,LAMBDA);
        if(!fit.converged()||!repeat.converged())throw new IllegalStateException("两头训练必须收敛");
        double difference=TrainingProbe.distance(fit.weights(),repeat.weights());
        if(difference>1e-12)throw new IllegalStateException("两头训练必须复现");
        return new Head(rows.stream().map(TrainingProbe.Row::date).toList(),scale.mean,scale.std,fit,difference);
    }
    static double predict(Head head,double[] x){
        double[] z=new double[x.length];for(int j=0;j<x.length;j++)z[j]=head.std()[j]==0?0:(x[j]-head.mean()[j])/head.std()[j];
        return BinaryFit.probability(head.fit().weights(),z);
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=2||!args[1].matches("[0-9a-f]{40}"))throw new IllegalArgumentException("需要新本地target结果路径及完整Git哈希");
        Path output=Path.of(args[0]).toAbsolutePath().normalize();
        if(!output.startsWith(Path.of("target").toAbsolutePath().normalize())||Files.exists(output))throw new IllegalArgumentException("只导出新的本地忽略结果，不覆盖已有文件");
        HierarchyChecks.main(new String[0]);
        var json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        JsonNode tree=json.readTree(TREE.toFile());
        if(tree.path("promotionAllowed").asBoolean()||!tree.path("cutoffExclusive").asText().equals("2024-11-11")||tree.path("inputs").size()!=4361)throw new IllegalStateException("冻结输入范围不符");
        List<TrainingProbe.Row> inputs=new ArrayList<>();Map<String,TrainingProbe.Row> dates=new HashMap<>();
        for(JsonNode row:tree.path("inputs")){
            String date=row.path("date").asText(),target=row.path("target").asText();
            if(target.compareTo("2024-11-11")>=0)throw new IllegalStateException("禁止读取最终留出日期");
            var parsed=new TrainingProbe.Row(LocalDate.parse(date),LocalDate.parse(target),json.convertValue(row.path("x"),double[].class),ForecastDirection.valueOf(row.path("actual").asText()));
            inputs.add(parsed);if(dates.put(date,parsed)!=null)throw new IllegalStateException("输入日期重复");
        }
        List<Fold> folds=new ArrayList<>();int i=0;
        for(JsonNode saved:tree.path("folds")){
            String start=saved.path("start").asText();var all=HierarchyModel.settled(inputs,LocalDate.parse(start));
            if(all.size()!=3640+i*240)throw new IllegalStateException("训练数量变化");
            var move=train(all,false);var side=train(HierarchyModel.directions(all),true);
            List<Prediction> predictions=new ArrayList<>();
            for(JsonNode row:saved.path("predictions")){
                String date=row.path("date").asText();var in=dates.get(date);
                if(in==null||!in.target().toString().equals(row.path("target").asText())||!in.label().name().equals(row.path("actual").asText()))throw new IllegalStateException("验证日期目标或标签不符");
                double pMove=predict(move,in.x()),pSide=predict(side,in.x());
                predictions.add(new Prediction(date,in.target().toString(),in.label().name(),pMove,pSide,HierarchyModel.combine(pMove,pSide),
                        json.convertValue(row.path("reference"),double[].class),json.convertValue(row.path("pitReference"),double[].class),json.convertValue(row.path("prior"),double[].class)));
            }
            if(predictions.size()!=240)throw new IllegalStateException("验证数量不符");
            folds.add(new Fold(start,saved.path("end").asText(),move,side,predictions,Math.max(move.repeatDifference(),side.repeatDifference())));i++;
            System.out.println("完成真实折 "+start+"：波动训练="+all.size()+"，方向训练="+side.dates().size());
        }
        if(folds.size()!=3)throw new IllegalStateException("验证折数量不符");
        Map<String,Object> out=new LinkedHashMap<>();out.put("version","gold-hierarchy-v1");out.put("gitCommit",args[1]);out.put("createdAt",OffsetDateTime.now());
        out.put("treeHash",TrainingProbe.sha(Files.readAllBytes(TREE)));out.put("inputHash",tree.path("inputHash").asText());
        out.put("lambda",LAMBDA);out.put("signalThreshold",.55);out.put("cutoffExclusive","2024-11-11");out.put("promotionAllowed",false);
        out.put("protocolHash",TrainingProbe.sha(Files.readAllBytes(Path.of("../docs/research/2026-10-03-hierarchy-protocol.md"))));
        Map<String,String> sources=new LinkedHashMap<>();for(String n:List.of("HierarchyRun.java","HierarchyModel.java","HierarchyChecks.java","BinaryFit.java","HistorySlice.java","FusionScale.java","TrainingProbe.java"))sources.put(n,TrainingProbe.sha(Files.readAllBytes(Path.of("../docs/research/probes/"+n))));
        out.put("sourceHashes",sources);out.put("folds",folds);json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),out);
    }
}
