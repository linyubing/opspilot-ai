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
import java.util.function.Predicate;

/** 冻结模型的真实价格状态审计；不训练新正式模型，不写数据库。 */
public class StateRun {
    static final Path REFERENCE=Path.of("../docs/research/2026-10-02-ridge-check.json");
    record TrainingRow(LocalDate date,LocalDate target,String actual,StateCuts.Input input) {}
    record Row(LocalDate date,LocalDate target,String actual,StateCuts.Input input,double priorTrend,int cell,
               double[] reference,double[] prior,double[] statePrior) {}
    record Group(String name,int count,boolean small,MacroModelProbe.Scores reference,MacroModelProbe.Scores prior,
                 MacroModelProbe.Scores statePrior,ForecastMetrics priorOnReferenceSignals,ForecastMetrics priorOnStateSignals) {}
    record Fold(LocalDate start,LocalDate end,LocalDate trainStart,LocalDate trainTargetEnd,int trainingCount,
                StateCuts.Cuts cuts,int[][] cellCounts,int[] globalCounts,List<TrainingRow> training,
                List<Row> predictions,List<Group> groups,double referenceDifference) {}
    static StateCuts.Input input(GoldFeatures f){var v=f.values();return new StateCuts.Input(v.get("volatility20"),v.get("overnightGap"),v.get("return1"),v.get("return20"));}
    static boolean member(String name,int cell){return switch(name){
        case "ALL"->true;case "HIGH_VOL"->(cell&4)!=0;case "OTHER_VOL"->(cell&4)==0;
        case "LARGE_GAP"->(cell&2)!=0;case "SMALL_GAP"->(cell&2)==0;case "CONFLICT"->(cell&1)!=0;case "NO_CONFLICT"->(cell&1)==0;
        default->cell==Integer.parseInt(name.substring(5));};}
    public static void main(String[] args)throws Exception {
        if(args.length!=2||!args[1].matches("[0-9a-f]{40}"))throw new IllegalArgumentException("需要结果路径和完整Git哈希");
        Path output=Path.of(args[0]);if(Files.exists(output))throw new IllegalArgumentException("不覆盖已有研究结果");
        StateChecks.main(new String[0]);var json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        JsonNode reference=json.readTree(REFERENCE.toFile());
        if(!reference.path("version").asText().equals("fred-centered-ridge-v1")||reference.path("promotionAllowed").asBoolean())throw new IllegalStateException("参照研究来源异常");
        for(var item:reference.path("sourceHashes").properties())if(!item.getValue().asText().equals(TrainingProbe.sha(Files.readAllBytes(Path.of("../docs/research/probes/"+item.getKey())))))
            throw new IllegalStateException("参照源码摘要变化");
        List<GoldDailyBar> bars=TrainingProbe.readBars();String barHash=TrainingProbe.hashBars(bars);
        if(!barHash.equals("68b98d2cafb416118b5019bb59c87c33614c547148792a79aa854d2974d3b9f0"))throw new IllegalStateException("真实日线摘要变化");
        var gold=(GoldDailyBarRepository)Proxy.newProxyInstance(StateRun.class.getClassLoader(),new Class<?>[]{GoldDailyBarRepository.class},(proxy,method,p)->{
            if(method.getName().equals("findAll")&&p.length==2)return bars;
            if(method.getName().equals("findRecent")&&p.length==4){LocalDate end=(LocalDate)p[2];return bars.stream().filter(b->!b.priceDate().isAfter(end))
                    .sorted(Comparator.comparing(GoldDailyBar::priceDate).reversed()).limit((int)p[3]).toList();}
            throw new UnsupportedOperationException("研究禁止写入和无截止查询");
        });
        var latest=(MacroObservationRepository)Proxy.newProxyInstance(StateRun.class.getClassLoader(),new Class<?>[]{MacroObservationRepository.class},
                (proxy,method,p)->{throw new UnsupportedOperationException("禁止读取最新修订宏观值");});
        var snapshots=new GoldResearchSnapshotService(gold,latest,new RealRateFactorEvaluator(),new DollarIndexFactorEvaluator());
        var store=new FredHistoryStore(json,System.getenv("FRED_HISTORY_DIR"));
        GoldDataset dataset=new GoldDatasetBuilder(gold,snapshots,new GoldForecastRule(),new GoldFeatureCalculator(),store).build(ForecastHorizon.NEXT_DAY);
        String contentHash=MacroModelProbe.contentHash(dataset),datasetHash=new GoldDatasetFingerprint().hash(dataset);
        if(dataset.samples().size()!=1500||!contentHash.equals("4ce352acbb854dd4e7e6d2de67c3426f0f62f360b76cf267559d57df07ea0dd3")
                ||!datasetHash.equals(reference.path("datasetHash").asText())||!json.valueToTree(dataset.macroInput()).equals(reference.path("macroInput")))
            throw new IllegalStateException("真实样本或来源不匹配");
        List<String> groupNames=new ArrayList<>(List.of("ALL","HIGH_VOL","OTHER_VOL","LARGE_GAP","SMALL_GAP","CONFLICT","NO_CONFLICT"));
        for(int c=0;c<8;c++)groupNames.add("CELL_"+c);
        List<Fold> folds=new ArrayList<>();
        for(int i=0;i<3;i++){
            LocalDate start=MacroModelProbe.STARTS.get(i),end=MacroModelProbe.ENDS.get(i);
            var train=dataset.samples().stream().filter(s->s.asOfDate().isBefore(start)&&s.targetDate().isBefore(start)).toList();
            var validation=dataset.samples().stream().filter(s->!s.asOfDate().isBefore(start)&&!s.asOfDate().isAfter(end)).toList();
            if(train.size()!=779+i*240||validation.size()!=240||!validation.getLast().targetDate().isBefore(TrainingProbe.LIMIT))throw new IllegalStateException("固定分区变化");
            JsonNode oldFold=reference.path("folds").get(i);
            for(String key:List.of("start","end"))if(!oldFold.path(key).asText().equals((key.equals("start")?start:end).toString()))throw new IllegalStateException("参照日期分区变化");
            if(oldFold.path("trainingCount").asInt()!=train.size()||!oldFold.path("trainTargetEnd").asText().equals(train.getLast().targetDate().toString()))throw new IllegalStateException("参照训练分区变化");
            JsonNode old=null;for(JsonNode r:oldFold.path("results"))if(r.path("profile").asText().equals("OHLC_20")&&r.path("lambda").asDouble()==.01)old=r;
            if(old==null||!old.path("fit").path("converged").asBoolean())throw new IllegalStateException("冻结参照缺失");
            var names=FeatureProfile.OHLC_20.featureNames().stream().sorted().toList();var scale=FeatureScaler.fit(train,names);
            double[] weights=json.convertValue(old.path("fit").path("weights"),double[].class);
            var state=StateCuts.train(train.stream().map(s->input(s.features())).toArray(StateCuts.Input[]::new),train.stream().mapToInt(s->s.label().ordinal()).toArray());
            double[] prior=new double[3];for(var s:train)prior[s.label().ordinal()]+=1.0/train.size();
            GoldClassifier model=f->{double[] p=SoftmaxFit.probabilities(weights,scale.values(f));return new DirectionProbabilities(p[0],p[1],p[2]);};
            GoldClassifier baseline=f->new DirectionProbabilities(prior[0],prior[1],prior[2]);
            GoldClassifier control=f->{double[] p=state.predict(input(f));return new DirectionProbabilities(p[0],p[1],p[2]);};
            List<Row> rows=new ArrayList<>();double difference=0;
            for(int j=0;j<validation.size();j++){
                var s=validation.get(j);JsonNode before=old.path("predictions").get(j);
                if(!before.path("date").asText().equals(s.asOfDate().toString())||!before.path("target").asText().equals(s.targetDate().toString())
                        ||!before.path("actual").asText().equals(s.label().name()))throw new IllegalStateException("参照日期或标签变化");
                double[] p=MacroModelProbe.array(model.predict(s.features()));
                for(int c=0;c<3;c++){difference=Math.max(difference,Math.abs(p[c]-before.path("ridge").get(c).asDouble()));
                    if(Math.abs(prior[c]-before.path("prior").get(c).asDouble())>1e-12)throw new IllegalStateException("训练频率参照变化");}
                var in=input(s.features());rows.add(new Row(s.asOfDate(),s.targetDate(),s.label().name(),in,StateCuts.priorTrend(in),state.cuts().cell(in),
                        json.convertValue(before.path("ridge"),double[].class),prior.clone(),state.predict(in)));
            }
            if(difference>1e-12)throw new IllegalStateException("冻结模型不能同特征复现");
            List<Group> groups=new ArrayList<>();
            for(String name:groupNames){
                var selected=validation.stream().filter(s->member(name,state.cuts().cell(input(s.features())))).toList();
                groups.add(new Group(name,selected.size(),selected.size()<30,selected.isEmpty()?null:MacroModelProbe.score(model,selected),
                        selected.isEmpty()?null:MacroModelProbe.score(baseline,selected),selected.isEmpty()?null:MacroModelProbe.score(control,selected),
                        signalPrior(selected,model,baseline),signalPrior(selected,control,baseline)));
            }
            folds.add(new Fold(start,end,train.getFirst().asOfDate(),train.getLast().targetDate(),train.size(),state.cuts(),state.counts(),state.global(),
                    train.stream().map(s->new TrainingRow(s.asOfDate(),s.targetDate(),s.label().name(),input(s.features()))).toList(),rows,groups,difference));
            System.out.println(start+" vol75="+state.cuts().volatility()+" gap75="+state.cuts().gap()+" stateAccuracy="+groups.getFirst().statePrior().all().accuracy());
        }
        Map<String,Object> report=new LinkedHashMap<>();report.put("version","fred-price-state-audit-v1");report.put("gitCommit",args[1]);report.put("createdAt",OffsetDateTime.now());
        report.put("barHash",barHash);report.put("contentHash",contentHash);report.put("datasetHash",datasetHash);report.put("macroInput",dataset.macroInput());
        report.put("referenceHash",TrainingProbe.sha(Files.readString(REFERENCE).replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        report.put("promotionAllowed",false);report.put("cutoffExclusive",TrainingProbe.LIMIT);report.put("groupNames",groupNames);
        Map<String,String> hashes=new LinkedHashMap<>();for(String file:List.of("StateCuts.java","StateChecks.java","StateRun.java","SoftmaxFit.java","MacroModelProbe.java","TrainingProbe.java"))
            hashes.put(file,TrainingProbe.sha(Files.readAllBytes(Path.of("../docs/research/probes/"+file))));report.put("sourceHashes",hashes);
        report.put("protocolHash",TrainingProbe.sha(Files.readAllBytes(Path.of("../docs/research/2026-10-02-state-protocol.md"))));report.put("folds",folds);
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);System.out.println("REPORT "+output);
    }
    static ForecastMetrics signalPrior(List<GoldSample> samples,GoldClassifier model,GoldClassifier baseline){
        var selected=samples.stream().filter(s->Arrays.stream(MacroModelProbe.array(model.predict(s.features()))).max().orElseThrow()>=.55).toList();
        return selected.isEmpty()?null:MacroModelProbe.score(baseline,selected).all();
    }
}
