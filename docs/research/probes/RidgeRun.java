package com.opspilot.ai.forecast.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opspilot.ai.analysis.*;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.macrodata.*;
import com.opspilot.ai.marketdata.*;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/** 固定强度的真实数据正则化研究；只读、独立留档，不发布正式预测。 */
public class RidgeRun {
    static final List<Double> STRENGTHS=List.of(.01,.1,1.0);
    static final Path REFERENCE=Path.of("../docs/research/2026-10-02-macro-model-check.json");
    record Row(LocalDate date,LocalDate target,String actual,double[] ridge,double[] reference,double[] prior) {}
    record Result(FeatureProfile profile,double lambda,SoftmaxFit.Fit fit,MacroModelProbe.Scores training,
                  MacroModelProbe.Scores validation,ForecastMetrics priorOnSignals,List<LocalDate> selectedDates,
                  List<Row> predictions,double repeatDifference,double referenceDifference) {}
    record Fold(LocalDate start,LocalDate end,LocalDate trainStart,LocalDate trainTargetEnd,int trainingCount,List<Result> results) {}
    public static void main(String[] args)throws Exception {
        if(args.length!=2||!args[1].matches("[0-9a-f]{40}"))throw new IllegalArgumentException("需要结果路径和完整 Git 哈希");
        Path output=Path.of(args[0]);if(Files.exists(output))throw new IllegalArgumentException("不覆盖已有研究结果");
        RidgeChecks.main(new String[0]);
        var json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        JsonNode reference=json.readTree(REFERENCE.toFile());
        if(!reference.path("version").asText().equals("fred-fixed-nonlinear-v1")||reference.path("promotionAllowed").asBoolean())
            throw new IllegalStateException("无正则参照来源不符合固定协议");
        for(var item:reference.path("sourceHashes").properties())
            if(!item.getValue().asText().equals(TrainingProbe.sha(Files.readAllBytes(Path.of("../docs/research/probes/"+item.getKey())))))
                throw new IllegalStateException("参照研究源码摘要变化");
        List<GoldDailyBar> bars=TrainingProbe.readBars();String barHash=TrainingProbe.hashBars(bars);
        if(!barHash.equals("68b98d2cafb416118b5019bb59c87c33614c547148792a79aa854d2974d3b9f0"))throw new IllegalStateException("真实日线指纹变化");
        var gold=(GoldDailyBarRepository)Proxy.newProxyInstance(RidgeRun.class.getClassLoader(),new Class<?>[]{GoldDailyBarRepository.class},(proxy,method,p)->{
            if(method.getName().equals("findAll")&&p.length==2)return bars;
            if(method.getName().equals("findRecent")&&p.length==4){LocalDate end=(LocalDate)p[2];return bars.stream()
                    .filter(b->!b.priceDate().isAfter(end)).sorted(Comparator.comparing(GoldDailyBar::priceDate).reversed()).limit((int)p[3]).toList();}
            throw new UnsupportedOperationException("研究禁止写入和无截止查询");
        });
        var latest=(MacroObservationRepository)Proxy.newProxyInstance(RidgeRun.class.getClassLoader(),new Class<?>[]{MacroObservationRepository.class},
                (proxy,method,p)->{throw new UnsupportedOperationException("禁止读取最新修订宏观值");});
        var snapshots=new GoldResearchSnapshotService(gold,latest,new RealRateFactorEvaluator(),new DollarIndexFactorEvaluator());
        var store=new FredHistoryStore(json,System.getenv("FRED_HISTORY_DIR"));
        GoldDataset dataset=new GoldDatasetBuilder(gold,snapshots,new GoldForecastRule(),new GoldFeatureCalculator(),store).build(ForecastHorizon.NEXT_DAY);
        String contentHash=MacroModelProbe.contentHash(dataset),datasetHash=new GoldDatasetFingerprint().hash(dataset);
        if(dataset.samples().size()!=1500||!contentHash.equals("4ce352acbb854dd4e7e6d2de67c3426f0f62f360b76cf267559d57df07ea0dd3")
                ||!datasetHash.equals(reference.path("datasetHash").asText())||!json.valueToTree(dataset.macroInput()).equals(reference.path("macroInput")))
            throw new IllegalStateException("真实样本或来源与无正则参照不同");
        List<Fold> folds=new ArrayList<>();
        for(int foldIndex=0;foldIndex<3;foldIndex++) {
            LocalDate start=MacroModelProbe.STARTS.get(foldIndex),end=MacroModelProbe.ENDS.get(foldIndex);
            var train=dataset.samples().stream().filter(s->s.asOfDate().isBefore(start)&&s.targetDate().isBefore(start)).toList();
            var validation=dataset.samples().stream().filter(s->!s.asOfDate().isBefore(start)&&!s.asOfDate().isAfter(end)).toList();
            if(train.size()!=779+foldIndex*240||validation.size()!=240||!validation.getLast().targetDate().isBefore(TrainingProbe.LIMIT))
                throw new IllegalStateException("固定分区变化");
            JsonNode oldFold=reference.path("folds").get(foldIndex);
            if(!oldFold.path("start").asText().equals(start.toString())||!oldFold.path("end").asText().equals(end.toString())
                    ||oldFold.path("trainingCount").asInt()!=train.size()
                    ||!oldFold.path("trainTargetEnd").asText().equals(train.getLast().targetDate().toString()))
                throw new IllegalStateException("无正则参照时间分区不一致");
            double[] prior=new double[3];for(var s:train)prior[s.label().ordinal()]+=1.0/train.size();
            GoldClassifier baseline=f->new DirectionProbabilities(prior[0],prior[1],prior[2]);
            List<Result> results=new ArrayList<>();
            for(FeatureProfile profile:FeatureProfile.values()) {
                var names=profile.featureNames().stream().sorted().toList();var scale=FeatureScaler.fit(train,names);
                double[][] x=train.stream().map(s->scale.values(s.features())).toArray(double[][]::new);int[] y=train.stream().mapToInt(s->s.label().ordinal()).toArray();
                JsonNode old=oldFold.path("comparisons").get(profile.ordinal());
                if(!old.path("profile").asText().equals(profile.name())||!old.path("fit").path("converged").asBoolean()
                        ||old.path("predictions").size()!=validation.size())throw new IllegalStateException("参照组合不一致或未收敛");
                double[] oldWeights=json.convertValue(old.path("fit").path("weights"),double[].class);
                double referenceDifference=0;
                for(int j=0;j<validation.size();j++) {
                    var s=validation.get(j);JsonNode row=old.path("predictions").get(j);
                    if(!row.path("date").asText().equals(s.asOfDate().toString())||!row.path("target").asText().equals(s.targetDate().toString())
                            ||!row.path("actual").asText().equals(s.label().name()))throw new IllegalStateException("参照日期或标签不一致");
                    double[] p=SoftmaxFit.probabilities(oldWeights,scale.values(s.features()));
                    for(int c=0;c<3;c++){
                        referenceDifference=Math.max(referenceDifference,Math.abs(p[c]-row.path("linear").get(c).asDouble()));
                        if(Math.abs(prior[c]-row.path("prior").get(c).asDouble())>1e-12)throw new IllegalStateException("参照基线频率不一致");
                    }
                }
                if(referenceDifference>1e-12)throw new IllegalStateException("无正则参照无法在同特征与标准化下复现");
                for(double lambda:STRENGTHS) {
                    var fit=RidgeFit.fit(x,y,lambda);
                    if(!fit.converged()){
                        results.add(new Result(profile,lambda,fit,null,null,null,List.of(),List.of(),0,referenceDifference));
                        System.out.println(start+" "+profile+" lambda="+lambda+" "+fit.status());continue;
                    }
                    var repeat=RidgeFit.fit(x,y,lambda);if(!repeat.converged())throw new IllegalStateException("重复训练未收敛");
                    GoldClassifier model=f->{double[] p=SoftmaxFit.probabilities(fit.weights(),scale.values(f));return new DirectionProbabilities(p[0],p[1],p[2]);};
                    List<Row> rows=new ArrayList<>();List<GoldSample> selected=new ArrayList<>();double difference=0;
                    for(int j=0;j<validation.size();j++) {
                        var s=validation.get(j);double[] p=MacroModelProbe.array(model.predict(s.features()));
                        double[] q=SoftmaxFit.probabilities(repeat.weights(),scale.values(s.features()));
                        for(int c=0;c<3;c++)difference=Math.max(difference,Math.abs(p[c]-q[c]));
                        if(Arrays.stream(p).max().orElseThrow()>=.55)selected.add(s);
                        rows.add(new Row(s.asOfDate(),s.targetDate(),s.label().name(),p,json.convertValue(old.path("predictions").get(j).path("linear"),double[].class),prior.clone()));
                    }
                    if(difference>1e-12)throw new IllegalStateException("固定正则化模型无法复现");
                    results.add(new Result(profile,lambda,fit,MacroModelProbe.score(model,train),MacroModelProbe.score(model,validation),
                            selected.isEmpty()?null:MacroModelProbe.score(baseline,selected).all(),selected.stream().map(GoldSample::asOfDate).toList(),rows,difference,referenceDifference));
                    System.out.println(start+" "+profile+" lambda="+lambda+" accuracy="+results.getLast().validation().all().accuracy()+" condition="+fit.trace().getLast().condition());
                }
            }
            folds.add(new Fold(start,end,train.getFirst().asOfDate(),train.getLast().targetDate(),train.size(),results));
        }
        Map<String,Object> report=new LinkedHashMap<>();report.put("version","fred-centered-ridge-v1");report.put("gitCommit",args[1]);report.put("createdAt",OffsetDateTime.now());
        report.put("barHash",barHash);report.put("contentHash",contentHash);report.put("datasetHash",datasetHash);report.put("macroInput",dataset.macroInput());
        report.put("referenceHash",TrainingProbe.sha(Files.readString(REFERENCE).replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        report.put("strengths",STRENGTHS);report.put("promotionAllowed",false);report.put("cutoffExclusive",TrainingProbe.LIMIT);
        Map<String,String> hashes=new LinkedHashMap<>();for(String file:List.of("RidgeFit.java","RidgeChecks.java","RidgeRun.java","SoftmaxFit.java","MacroModelProbe.java","TrainingProbe.java"))
            hashes.put(file,TrainingProbe.sha(Files.readAllBytes(Path.of("../docs/research/probes/"+file))));report.put("sourceHashes",hashes);
        report.put("protocolHash",TrainingProbe.sha(Files.readAllBytes(Path.of("../docs/research/2026-10-02-ridge-protocol.md"))));report.put("folds",folds);
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);System.out.println("REPORT "+output);
    }
}
