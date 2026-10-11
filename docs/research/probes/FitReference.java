package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** 保持真实人群和特征不变，仅替换研究求解器；不注册正式模型。 */
public class FitReference {
    record Fold(int fitCount, LocalDate fitTargetEnd, LocalDate diagnosticStart,
                SoftmaxFit.Fit solver, FitAudit.Group fit, FitAudit.Group diagnostic,
                ForecastMetrics signals, ForecastMetrics priorOnSignals) {}
    public static void main(String[] args) throws Exception {
        if(args.length!=4 || !args[3].matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("需要冻结输入、旧训练体检、新输出和提交哈希");
        String input=Files.readString(Path.of(args[0]),StandardCharsets.UTF_8);
        var data=PriorCompare.JSON.readValue(input,GoldDataset.class);
        if(!PriorCompare.HASH.equals(new GoldDatasetFingerprint().hash(data)) || data.samples().size()!=1268)
            throw new IllegalStateException("冻结人群已变化");
        for(var s:data.samples())if(!s.targetDate().isBefore(PriorCompare.LIMIT))
            throw new IllegalStateException("禁止读取封闭期");
        String oldRaw=Files.readString(Path.of(args[1]),StandardCharsets.UTF_8);
        var old=PriorCompare.JSON.readTree(oldRaw);
        if(!PriorCompare.sha(input).equals(old.path("inputHash").asText()))
            throw new IllegalStateException("原体检不是同一输入");
        Path output=Path.of(args[2]);if(Files.exists(output))throw new IllegalArgumentException("禁止覆盖实验");
        Files.writeString(Path.of(args[2]+".started"),Instant.now().toString(),StandardOpenOption.CREATE_NEW);
        var folds=new ArrayList<Fold>();
        var names=FeatureProfile.ALL_36.featureNames().stream().sorted().toList();
        for(int index=0;index<3;index++) {
            var outer=PriorCompare.training(data.samples(),548+240*index);
            var diagnostic=outer.subList(outer.size()-120,outer.size());
            var train=outer.subList(0,outer.size()-120).stream()
                    .filter(x->x.targetDate().isBefore(diagnostic.getFirst().asOfDate())).toList();
            if(train.size()!=426+240*index)throw new IllegalStateException("训练范围变化");
            var counts=PriorCompare.counts(train);
            var prior=new DirectionProbabilities((double)counts.get(ForecastDirection.BULLISH)/train.size(),
                    (double)counts.get(ForecastDirection.NEUTRAL)/train.size(),(double)counts.get(ForecastDirection.BEARISH)/train.size());
            var scale=FeatureScaler.fit(train,names);
            // 求解器只得到训练矩阵和已结算标签，不能查看诊断成绩决定停止。
            var solver=SoftmaxFit.fit(train.stream().map(x->scale.values(x.features())).toArray(double[][]::new),
                    train.stream().mapToInt(x->x.label().ordinal()).toArray());
            if(!solver.converged()) {
                folds.add(new Fold(train.size(),train.getLast().targetDate(),diagnostic.getFirst().asOfDate(),solver,null,null,null,null));
                System.out.println("FOLD="+(index+1)+" FAILED="+solver.status());continue;
            }
            GoldClassifier model=features->{
                double[] p=SoftmaxFit.probabilities(solver.weights(),scale.values(features));
                return new DirectionProbabilities(p[0],p[1],p[2]);
            };
            var fit=FitAudit.group(train,model,prior,scale);
            var test=FitAudit.group(diagnostic,model,prior,scale);
            for(int i=0;i<test.rows().size();i++) {
                var saved=old.path("folds").get(index).path("diagnostic").path("rows").get(i);
                var row=test.rows().get(i);
                if(!row.date().toString().equals(saved.path("date").asText())
                        || !row.target().toString().equals(saved.path("target").asText())
                        || !row.actual().name().equals(saved.path("actual").asText())
                        || !PriorCompare.JSON.valueToTree(row.prior()).equals(saved.path("prior")))
                    throw new IllegalStateException("旧日期标签或频率基线变化");
            }
            var signals=new ArrayList<SettledPrediction>();var selectedPrior=new ArrayList<SettledPrediction>();
            for(int i=0;i<diagnostic.size();i++) {
                var sample=diagnostic.get(i);var signal=PriorCompare.settle(sample,test.rows().get(i).model(),true);
                signals.add(signal);if(signal.prediction().status()==SignalStatus.PREDICTED)
                    selectedPrior.add(PriorCompare.settle(sample,prior,false));
            }
            var evaluator=new ForecastEvaluator();
            folds.add(new Fold(train.size(),train.getLast().targetDate(),diagnostic.getFirst().asOfDate(),solver,
                    fit,test,evaluator.evaluate(signals),selectedPrior.isEmpty()?null:evaluator.evaluate(selectedPrior)));
            System.out.println("FOLD="+(index+1)+" gradient="+solver.trace().getLast().gradient()
                    +" trainLoss="+fit.model().logLoss()+" diagnosticAcc="+test.model().accuracy()
                    +" priorAcc="+test.prior().accuracy());
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("version","gold-fit-reference-v1");result.put("gitCommit",args[3]);result.put("createdAt",OffsetDateTime.now(ZoneOffset.UTC));
        result.put("inputHash",PriorCompare.sha(input));result.put("oldAuditHash",PriorCompare.sha(oldRaw));
        result.put("datasetHash",PriorCompare.HASH);result.put("holdoutRead",false);result.put("promotionAllowed",false);
        result.put("featureNames",names);
        var sources=new TreeMap<String,String>();
        for(String source:List.of("FitReference.java","SoftmaxFit.java","FitAudit.java","PriorCompare.java","CalibrationProbe.java"))
            sources.put(source,PriorCompare.sha(Files.readString(Path.of("../docs/research/probes/"+source),StandardCharsets.UTF_8)));
        result.put("sourceHashes",sources);result.put("folds",folds);
        Files.writeString(output,PriorCompare.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result).replace("\r\n","\n")+"\n",
                StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }
}
