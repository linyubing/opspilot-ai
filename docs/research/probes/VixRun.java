package com.opspilot.ai.forecast.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/** 固定协议的真实VIX增量研究；两个分支共享训练日期，不发布正式模型。 */
public final class VixRun {
    record Model(double[] mean,double[] std,SoftmaxFit.Fit fit,double repeatDifference) {}
    record Prediction(String date,String target,String actual,double[] price,double[] candidate,double[] prior,double[] fullPrice) {}
    record Fold(String start,String end,List<String> trainingDates,Model price,Model candidate,
                List<Prediction> training,List<Prediction> predictions) {}
    static final List<String> LABELS=List.of("BULLISH","NEUTRAL","BEARISH");
    static final List<String> CODE=List.of("VixRun.java","VixCohort.java","VixCohortChecks.java",
            "FusionScale.java","RidgeFit.java","SoftmaxFit.java");
    public static void main(String[] args) throws Exception {
        if(args.length!=3||!args[2].matches("[0-9a-f]{40}"))throw new IllegalArgumentException("需要真实特征、新结果路径与完整Git哈希");
        Path target=Path.of("target").toRealPath(),out=Path.of(args[1]).toAbsolutePath().normalize();
        if(!out.startsWith(target)||out.equals(target)||Files.exists(out)||!out.getParent().toRealPath().startsWith(target))
            throw new IllegalArgumentException("只允许新的target内研究文件");
        VixCohortChecks.main(new String[0]);ObjectMapper json=new ObjectMapper();
        Path treePath=Path.of("../docs/research/2026-10-03-tree-check.json");
        byte[] treeBytes=Files.readAllBytes(treePath),vectorBytes=Files.readAllBytes(Path.of(args[0]));
        var tree=json.readTree(treeBytes);var vectors=json.readTree(vectorBytes);
        String protocol=sha(Files.readAllBytes(Path.of("../docs/research/2026-10-03-vix-protocol.md")));
        if(!vectors.path("version").asText().equals("vix-known-before-day-four-v1")
                ||!vectors.path("treeHash").asText().equals(sha(treeBytes))
                ||!vectors.path("protocolHash").asText().equals(protocol)
                ||vectors.path("promotionAllowed").asBoolean()||tree.path("promotionAllowed").asBoolean())
            throw new IllegalArgumentException("VIX输入或协议来源变化");
        for(var entry:vectors.path("codeHashes").properties()){
            Path file=entry.getKey().equals("FredHistory.java")?Path.of("src/main/java/com/opspilot/ai/macrodata/FredHistory.java")
                    :Path.of("../docs/research/probes",entry.getKey());
            if(!entry.getValue().asText().equals(sha(Files.readAllBytes(file))))throw new IllegalArgumentException("VIX计算源码变化");
        }
        if(tree.path("inputs").size()!=4361||vectors.path("vectors").size()!=4361)throw new IllegalArgumentException("真实输入数量变化");
        List<VixCohort.Row> inputs=new ArrayList<>();
        for(int i=0;i<4361;i++){
            var raw=tree.path("inputs").get(i);var risk=vectors.path("vectors").get(i);
            if(!raw.path("date").asText().equals(risk.path("date").asText())||!raw.path("target").asText().equals(risk.path("target").asText()))
                throw new IllegalArgumentException("价格与VIX日期未对齐");
            inputs.add(new VixCohort.Row(LocalDate.parse(raw.path("date").asText()),LocalDate.parse(raw.path("target").asText()),raw.path("actual").asText(),
                    json.convertValue(raw.path("x"),double[].class),risk.path("values").isNull()?null:json.convertValue(risk.path("values"),double[].class)));
        }
        List<Fold> folds=new ArrayList<>();
        for(int i=0;i<3;i++){
            var old=tree.path("folds").get(i);String startText=old.path("start").asText(),endText=old.path("end").asText();
            LocalDate start=LocalDate.parse(startText),end=LocalDate.parse(endText);
            var train=VixCohort.training(inputs,start);
            var validation=inputs.stream().filter(r->!r.date().isBefore(start)&&!r.date().isAfter(end)).toList();
            if(train.size()!=2915+i*240||validation.size()!=240||validation.stream().anyMatch(r->r.risk()==null))
                throw new IllegalStateException("匹配训练或完整验证数量变化");
            var price=fit(train,false);var candidate=fit(train,true);double[] prior=new double[3];
            for(var row:train)prior[LABELS.indexOf(row.actual())]+=1.0/train.size();
            List<Prediction> training=new ArrayList<>(),predictions=new ArrayList<>();
            for(var row:train)training.add(predict(row,price,candidate,prior,null));
            for(int j=0;j<validation.size();j++){
                var row=validation.get(j);var oldRow=old.path("predictions").get(j);
                if(!oldRow.path("date").asText().equals(row.date().toString())||!oldRow.path("actual").asText().equals(row.actual()))
                    throw new IllegalArgumentException("验证日期或标签变化");
                predictions.add(predict(row,price,candidate,prior,json.convertValue(oldRow.path("reference"),double[].class)));
            }
            folds.add(new Fold(startText,endText,train.stream().map(r->r.date().toString()).toList(),price,candidate,training,predictions));
            System.out.println("VIX_FOLD="+startText+" train="+train.size()+" validation="+validation.size());
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("version","vix-matched-ridge-v1");
        result.put("createdAt",OffsetDateTime.now().toString());result.put("gitCommit",args[2]);result.put("lambda",.01);
        result.put("signalThreshold",.55);result.put("cutoffExclusive","2024-11-11");result.put("promotionAllowed",false);
        result.put("treeHash",sha(treeBytes));result.put("vectorsHash",sha(vectorBytes));result.put("protocolHash",protocol);
        Map<String,String> code=new LinkedHashMap<>();for(String name:CODE)code.put(name,sha(Files.readAllBytes(Path.of("../docs/research/probes",name))));
        result.put("sourceHashes",code);result.put("folds",folds);
        Files.writeString(out,json.writeValueAsString(result),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        System.out.println("VIX_EXPERIMENT_COMPLETE");
    }
    private static Model fit(List<VixCohort.Row> rows,boolean risk){
        double[][] raw=rows.stream().map(r->risk?VixCohort.combine(r):r.price()).toArray(double[][]::new);
        var scale=new FusionScale(raw);double[][] x=Arrays.stream(raw).map(scale::values).toArray(double[][]::new);
        int[] y=rows.stream().mapToInt(r->LABELS.indexOf(r.actual())).toArray();
        var fit=RidgeFit.fit(x,y,.01);var repeat=RidgeFit.fit(x,y,.01);
        if(!fit.converged()||!repeat.converged())throw new IllegalStateException("VIX对照模型未收敛，不调整参数补考");
        double diff=0;for(double[] row:x){var a=SoftmaxFit.probabilities(fit.weights(),row);var b=SoftmaxFit.probabilities(repeat.weights(),row);
            for(int j=0;j<3;j++)diff=Math.max(diff,Math.abs(a[j]-b[j]));}
        if(diff>1e-12)throw new IllegalStateException("重复训练结果不一致");
        return new Model(scale.mean,scale.std,fit,diff);
    }
    private static Prediction predict(VixCohort.Row row,Model price,Model candidate,double[] prior,double[] full){
        return new Prediction(row.date().toString(),row.target().toString(),row.actual(),probabilities(price,row.price()),
                probabilities(candidate,VixCohort.combine(row)),prior.clone(),full);
    }
    private static double[] probabilities(Model model,double[] raw){
        double[] x=new double[raw.length];for(int j=0;j<x.length;j++)x[j]=model.std()[j]==0?0:(raw[j]-model.mean()[j])/model.std()[j];
        return SoftmaxFit.probabilities(model.fit().weights(),x);
    }
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
