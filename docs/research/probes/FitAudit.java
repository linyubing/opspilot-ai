package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** 固定模型的训练能力体检；只复现既有概率，不调参、不写数据库。 */
public class FitAudit {
    record Shift(double maxAbsZ, int overFiveCount) {}
    record Group(ForecastMetrics model, ForecastMetrics prior, Shift shift, List<CalibrationProbe.Row> rows) {}
    record Fold(int fitCount, LocalDate fitTargetEnd, LocalDate diagnosticStart,
                Map<ForecastDirection,Integer> fitCounts, Group fit, Group diagnostic) {}

    public static void main(String[] args) throws Exception {
        if(args.length!=4 || !args[3].matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("需要冻结输入、旧概率、全新输出和提交哈希");
        String input=Files.readString(Path.of(args[0]),StandardCharsets.UTF_8);
        var data=PriorCompare.JSON.readValue(input,GoldDataset.class);
        if(!PriorCompare.HASH.equals(new GoldDatasetFingerprint().hash(data)) || data.samples().size()!=1268)
            throw new IllegalStateException("真实样本指纹变化");
        for(var sample:data.samples())if(!sample.targetDate().isBefore(PriorCompare.LIMIT))
            throw new IllegalStateException("禁止读取封闭期");
        String oldRaw=Files.readString(Path.of(args[1]),StandardCharsets.UTF_8);
        var old=PriorCompare.JSON.readTree(oldRaw);
        if(!PriorCompare.sha(input).equals(old.path("inputHash").asText()))
            throw new IllegalStateException("原实验不是同一冻结输入");
        Path out=Path.of(args[2]);
        if(Files.exists(out))throw new IllegalArgumentException("禁止覆盖体检");
        Files.writeString(Path.of(args[2]+".started"),Instant.now().toString(),StandardOpenOption.CREATE_NEW);
        var folds=new ArrayList<Fold>();
        for(int index=0;index<3;index++) {
            var outer=PriorCompare.training(data.samples(),548+240*index);
            var diagnostic=outer.subList(outer.size()-120,outer.size());
            LocalDate first=diagnostic.getFirst().asOfDate();
            var fit=outer.subList(0,outer.size()-120).stream().filter(x->x.targetDate().isBefore(first)).toList();
            if(fit.size()!=426+240*index)throw new IllegalStateException("拟合区间变化");
            var counts=PriorCompare.counts(fit);
            var prior=new DirectionProbabilities((double)counts.get(ForecastDirection.BULLISH)/fit.size(),
                    (double)counts.get(ForecastDirection.NEUTRAL)/fit.size(),(double)counts.get(ForecastDirection.BEARISH)/fit.size());
            var model=new TribuoGoldTrainer(true,false).train(fit,FeatureProfile.ALL_36.featureNames());
            var names=FeatureProfile.ALL_36.featureNames().stream().sorted().toList();
            var scale=FeatureScaler.fit(fit,names);
            var trainGroup=group(fit,model,prior,scale);
            var testGroup=group(diagnostic,model,prior,scale);
            // 与已归档实验逐条对照，确认只是在增加训练诊断，未偷偷换模型。
            var saved=old.path("folds").get(index).path("calibration");
            if(saved.size()!=120)throw new IllegalStateException("原诊断区间不足");
            for(int i=0;i<120;i++)if(!PriorCompare.JSON.valueToTree(testGroup.rows().get(i)).equals(saved.get(i)))
                throw new IllegalStateException("旧模型概率未精确复现");
            folds.add(new Fold(fit.size(),fit.getLast().targetDate(),first,counts,trainGroup,testGroup));
            System.out.println("FOLD="+(index+1)+" trainLogLoss="+trainGroup.model().logLoss()
                    +" prior="+trainGroup.prior().logLoss()+" diagnosticLogLoss="+testGroup.model().logLoss());
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("version","gold-fit-audit-v1");result.put("gitCommit",args[3]);
        result.put("createdAt",OffsetDateTime.now(ZoneOffset.UTC));result.put("inputHash",PriorCompare.sha(input));
        result.put("oldProbabilityHash",PriorCompare.sha(oldRaw));result.put("datasetHash",PriorCompare.HASH);
        result.put("holdoutRead",false);result.put("promotionAllowed",false);result.put("newParametersTested",false);
        result.put("folds",folds);
        Files.writeString(out,PriorCompare.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result).replace("\r\n","\n")+"\n",
                StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }
    static Group group(List<GoldSample> samples,GoldClassifier model,DirectionProbabilities prior,FeatureScaler scale) {
        var rows=CalibrationProbe.predict(samples,model,prior);
        var evaluator=new ForecastEvaluator();
        var predicted=new ArrayList<SettledPrediction>();var baseline=new ArrayList<SettledPrediction>();
        double max=0;int over=0;
        for(int i=0;i<samples.size();i++) {
            var sample=samples.get(i);
            predicted.add(PriorCompare.settle(sample,rows.get(i).model(),false));
            baseline.add(PriorCompare.settle(sample,prior,false));
            double z=Arrays.stream(scale.values(sample.features())).map(Math::abs).max().orElseThrow();
            max=Math.max(max,z);if(z>5)over++;
        }
        return new Group(evaluator.evaluate(predicted),evaluator.evaluate(baseline),new Shift(max,over),rows);
    }
}
