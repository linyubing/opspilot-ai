package com.opspilot.ai.forecast.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opspilot.ai.analysis.*;
import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.macrodata.*;
import com.opspilot.ai.marketdata.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** 保留完整黄金历史的宏观概率后融合研究，仅只读输入，不发布正式模型。 */
public class FusionRun {
    static final Path REFERENCE=Path.of("../docs/research/2026-10-02-history-check.json");
    static final List<String> NAMES=List.of("dollar_age","dollar_return_1","dollar_return_20","dollar_return_5",
            "real_rate","real_rate_age","real_rate_bp_1","real_rate_bp_20","real_rate_bp_5");
    static final List<String> SOURCES=List.of(
            "docs/research/probes/FusionRun.java","docs/research/probes/FusionCohort.java",
            "docs/research/probes/FusionScale.java","docs/research/probes/FusionMix.java",
            "docs/research/probes/FusionMath.cjs","docs/research/probes/InfoMath.cjs",
            "docs/research/probes/RidgeFit.java","docs/research/probes/SoftmaxFit.java",
            "docs/research/probes/TrainingProbe.java","docs/research/probes/MacroModelProbe.java",
            "backend/src/main/java/com/opspilot/ai/forecast/learning/GoldDatasetBuilder.java",
            "backend/src/main/java/com/opspilot/ai/forecast/learning/FeatureScaler.java",
            "backend/src/main/java/com/opspilot/ai/analysis/GoldResearchSnapshotService.java",
            "backend/src/main/java/com/opspilot/ai/macrodata/FredHistoryStore.java",
            "backend/src/main/java/com/opspilot/ai/macrodata/FredHistory.java");
    record TrainRow(LocalDate date,LocalDate target,String actual,double[] macro) {}
    record Row(LocalDate date,LocalDate target,String actual,double[] price,double[] macro,
               double[] candidate,double[] frequencyMix,double[] prior,double[] pitReference) {}
    record Fold(LocalDate start,LocalDate end,List<LocalDate> trainingDates,double[] mean,double[] std,
                double[] frequencies,SoftmaxFit.Fit fit,List<TrainRow> training,List<Row> predictions,double repeatDifference) {}

    public static void main(String[] args)throws Exception {
        if(args.length!=2||!args[1].matches("[0-9a-f]{40}"))throw new IllegalArgumentException("需要新结果路径和完整Git哈希");
        Path output=Path.of(args[0]);if(Files.exists(output))throw new IllegalArgumentException("不覆盖已有研究结果");
        FusionChecks.main(new String[0]);FusionCohortChecks.main(new String[0]);
        var json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        JsonNode reference=json.readTree(REFERENCE.toFile());HistoryRun.checkSources(reference);
        if(!reference.path("version").asText().equals("ohlc-full-history-ridge-v1")||reference.path("promotionAllowed").asBoolean())
            throw new IllegalStateException("冻结价格参照不合法");
        var bars=TrainingProbe.readBars();String hash=TrainingProbe.hashBars(bars);
        if(bars.size()!=4382||!hash.equals("68b98d2cafb416118b5019bb59c87c33614c547148792a79aa854d2974d3b9f0")
                ||!hash.equals(reference.path("barHash").asText()))throw new IllegalStateException("真实日线指纹变化");
        // 限定真实已结算日线：禁止数据库写入、无截止日期查询及最新宏观修订值。
        var gold=(GoldDailyBarRepository)Proxy.newProxyInstance(FusionRun.class.getClassLoader(),new Class<?>[]{GoldDailyBarRepository.class},(proxy,method,p)->{
            if(method.getName().equals("findAll")&&p.length==2)return bars;
            if(method.getName().equals("findRecent")&&p.length==4)return bars.stream().filter(b->!b.priceDate().isAfter((LocalDate)p[2]))
                    .sorted(Comparator.comparing(GoldDailyBar::priceDate).reversed()).limit((int)p[3]).toList();
            throw new UnsupportedOperationException("研究禁止写入与无截止日查询");
        });
        var latest=(MacroObservationRepository)Proxy.newProxyInstance(FusionRun.class.getClassLoader(),new Class<?>[]{MacroObservationRepository.class},
                (proxy,method,p)->{throw new UnsupportedOperationException("研究禁止最新修订宏观输入");});
        var snapshots=new GoldResearchSnapshotService(gold,latest,new RealRateFactorEvaluator(),new DollarIndexFactorEvaluator());
        var history=new FredHistoryStore(json,System.getenv("FRED_HISTORY_DIR"));
        var dataset=new GoldDatasetBuilder(gold,snapshots,new GoldForecastRule(),new GoldFeatureCalculator(),history).build(ForecastHorizon.NEXT_DAY);
        String contentHash=MacroModelProbe.contentHash(dataset);
        if(dataset.samples().size()!=1500||!contentHash.equals("4ce352acbb854dd4e7e6d2de67c3426f0f62f360b76cf267559d57df07ea0dd3"))
            throw new IllegalStateException("生产真实样本内容变化");
        var inputs=dataset.samples().stream().map(s->new FusionCohort.Input(s.asOfDate(),s.targetDate(),s.label().name(),
                NAMES.stream().mapToDouble(n->s.features().values().get(n)).toArray())).toList();
        var byDate=new HashMap<LocalDate,FusionCohort.Input>();for(var row:inputs)byDate.put(row.date(),row);
        var priceInputs=new HashMap<String,JsonNode>();for(var raw:reference.path("inputs"))priceInputs.put(raw.path("date").asText(),raw);
        List<Fold> folds=new ArrayList<>();
        for(int i=0;i<3;i++) {
            JsonNode old=reference.path("folds").get(i);LocalDate start=MacroModelProbe.STARTS.get(i),end=MacroModelProbe.ENDS.get(i);
            if(!old.path("start").asText().equals(start.toString())||!old.path("end").asText().equals(end.toString()))
                throw new IllegalStateException("固定分区变化");
            var train=FusionCohort.training(inputs,start);if(train.size()!=779+i*240)throw new IllegalStateException("宏观训练数量变化");
            var scale=new FusionScale(train.stream().map(FusionCohort.Input::x).toArray(double[][]::new));
            var productionTrain=dataset.samples().stream().filter(s->s.asOfDate().isBefore(start)&&s.targetDate().isBefore(start)).toList();
            var productionScale=FeatureScaler.fit(productionTrain,NAMES);
            for(int j=0;j<train.size();j++)FusionChecks.near(scale.values(train.get(j).x()),productionScale.values(productionTrain.get(j).features()));
            double[][] x=train.stream().map(r->scale.values(r.x())).toArray(double[][]::new);
            int[] y=train.stream().mapToInt(r->ForecastDirection.valueOf(r.actual()).ordinal()).toArray();
            var fit=RidgeFit.fit(x,y,.01);var repeat=RidgeFit.fit(x,y,.01);
            if(!fit.converged()||!repeat.converged())throw new IllegalStateException("真实宏观拟合未收敛，禁止评分");
            double difference=TrainingProbe.distance(fit.weights(),repeat.weights());
            if(difference>1e-12)throw new IllegalStateException("重复拟合权重不一致");
            double[] frequencies=new double[3];for(int label:y)frequencies[label]+=1.0/y.length;
            List<TrainRow> training=new ArrayList<>();for(var r:train)training.add(new TrainRow(r.date(),r.target(),r.actual(),SoftmaxFit.probabilities(fit.weights(),scale.values(r.x()))));
            double[] mean=json.convertValue(old.path("mean"),double[].class),std=json.convertValue(old.path("std"),double[].class);
            double[] weights=json.convertValue(old.path("fit").path("weights"),double[].class);
            List<Row> rows=new ArrayList<>();
            for(var saved:old.path("predictions")) {
                String day=saved.path("date").asText();var raw=byDate.get(LocalDate.parse(day));var priceRaw=priceInputs.get(day);
                if(raw==null||!raw.target().toString().equals(saved.path("target").asText())||!raw.actual().equals(saved.path("actual").asText()))
                    throw new IllegalStateException("两个分支日期、目标或真实标签不一致");
                double[] px=json.convertValue(priceRaw.path("x"),double[].class);for(int j=0;j<px.length;j++)px[j]=std[j]==0?0:(px[j]-mean[j])/std[j];
                double[] price=SoftmaxFit.probabilities(weights,px),macro=SoftmaxFit.probabilities(fit.weights(),scale.values(raw.x()));
                FusionChecks.near(price,json.convertValue(saved.path("candidate"),double[].class));
                difference=Math.max(difference,TrainingProbe.distance(macro,SoftmaxFit.probabilities(repeat.weights(),scale.values(raw.x()))));
                rows.add(new Row(raw.date(),raw.target(),raw.actual(),price,macro,FusionMix.mix(price,macro),FusionMix.mix(price,frequencies),
                        json.convertValue(saved.path("newPrior"),double[].class),json.convertValue(saved.path("reference"),double[].class)));
            }
            if(rows.size()!=240||difference>1e-12)throw new IllegalStateException("验证数量或重复概率变化");
            folds.add(new Fold(start,end,train.stream().map(FusionCohort.Input::date).toList(),scale.mean,scale.std,frequencies,fit,training,rows,difference));
            System.out.println(start+" macroTrain="+train.size()+" gradient="+fit.trace().getLast().gradient());
        }
        Map<String,Object> report=new LinkedHashMap<>();report.put("version","gold-macro-late-fusion-v1");report.put("gitCommit",args[1]);
        report.put("createdAt",OffsetDateTime.now());report.put("promotionAllowed",false);report.put("cutoffExclusive",TrainingProbe.LIMIT);
        report.put("barHash",hash);report.put("contentHash",contentHash);report.put("referenceHash",TrainingProbe.sha(Files.readAllBytes(REFERENCE)));
        report.put("macroInput",dataset.macroInput());report.put("featureNames",NAMES);report.put("lambda",.01);report.put("priceWeight",.5);report.put("signalThreshold",.55);
        Map<String,String> hashes=new LinkedHashMap<>();for(String source:SOURCES)hashes.put(source,TrainingProbe.sha(Files.readAllBytes(Path.of("../"+source))));
        report.put("sourceHashes",hashes);report.put("protocolHash",TrainingProbe.sha(Files.readAllBytes(Path.of("../docs/research/2026-10-03-fusion-protocol.md"))));
        report.put("inputs",inputs);report.put("folds",folds);
        try(var stream=Files.newOutputStream(output,StandardOpenOption.CREATE_NEW)) {json.writerWithDefaultPrettyPrinter().writeValue(stream,report);}
        System.out.println("FUSION_EXPORT_PASS");
    }
}
