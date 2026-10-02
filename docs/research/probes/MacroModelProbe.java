package com.opspilot.ai.forecast.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opspilot.ai.analysis.*;
import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.macrodata.*;
import com.opspilot.ai.marketdata.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/** 真实宏观历史版本与固定非线性模型的配对研究，只读数据，不发布预测模型。 */
public class MacroModelProbe {
    static final List<LocalDate> STARTS=List.of(LocalDate.parse("2022-02-02"),LocalDate.parse("2023-01-04"),LocalDate.parse("2023-12-07"));
    static final List<LocalDate> ENDS=List.of(LocalDate.parse("2023-01-03"),LocalDate.parse("2023-12-06"),LocalDate.parse("2024-11-07"));
    static final XgboostProperties TREE=new XgboostProperties(200,.03,0,3,5,.8,.8,1,0,1,20260901L);
    record Scores(ForecastMetrics all,ForecastMetrics signals) {}
    record Row(LocalDate date,LocalDate target,String actual,double[] linear,double[] tree,double[] prior) {}
    record Compare(FeatureProfile profile,SoftmaxFit.Fit fit,Scores linearTrain,Scores treeTrain,
                   Scores linearValidation,Scores treeValidation,ForecastMetrics priorOnTreeSignals,
                   List<LocalDate> treeSelectedDates,List<Row> predictions,double treeRepeatDifference) {}
    record Fold(LocalDate start,LocalDate end,int trainingCount,LocalDate trainStart,LocalDate trainTargetEnd,
                int validationCount,Scores prior,List<Compare> comparisons,boolean allConverged) {}

    public static void main(String[] args)throws Exception {
        if(args.length!=2||!args[1].matches("[0-9a-f]{40}"))throw new IllegalArgumentException("需要结果路径和完整 Git 哈希");
        Path output=Path.of(args[0]);if(Files.exists(output))throw new IllegalArgumentException("不覆盖已有研究结果");
        SoftmaxChecks.main(new String[0]);
        var json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var bars=TrainingProbe.readBars();String barsHash=TrainingProbe.hashBars(bars);
        if(!barsHash.equals("68b98d2cafb416118b5019bb59c87c33614c547148792a79aa854d2974d3b9f0"))throw new IllegalStateException("真实日线指纹变化");
        // 内存仓储只有真实 SQL 已取得的受限日线；任何不受限查询或写操作都会失败。
        var gold=(GoldDailyBarRepository)Proxy.newProxyInstance(MacroModelProbe.class.getClassLoader(),new Class<?>[]{GoldDailyBarRepository.class},(proxy,method,p)->{
            if(method.getName().equals("findAll")&&p.length==2)return bars;
            if(method.getName().equals("findRecent")&&p.length==4){
                LocalDate end=(LocalDate)p[2];return bars.stream().filter(b->!b.priceDate().isAfter(end))
                        .sorted(Comparator.comparing(GoldDailyBar::priceDate).reversed()).limit((int)p[3]).toList();
            }
            throw new UnsupportedOperationException("研究仓储禁止写入和无截止日查询");
        });
        var latest=(MacroObservationRepository)Proxy.newProxyInstance(MacroModelProbe.class.getClassLoader(),new Class<?>[]{MacroObservationRepository.class},
                (proxy,method,p)->{throw new UnsupportedOperationException("本研究禁止使用最新修订宏观值");});
        var snapshots=new GoldResearchSnapshotService(gold,latest,new RealRateFactorEvaluator(),new DollarIndexFactorEvaluator());
        var history=new FredHistoryStore(json,System.getenv("FRED_HISTORY_DIR"));
        GoldDataset dataset=new GoldDatasetBuilder(gold,snapshots,new GoldForecastRule(),new GoldFeatureCalculator(),history).build(ForecastHorizon.NEXT_DAY);
        String contentHash=contentHash(dataset);
        if(dataset.samples().size()!=1500||!contentHash.equals("4ce352acbb854dd4e7e6d2de67c3426f0f62f360b76cf267559d57df07ea0dd3"))
            throw new IllegalStateException("已验证的真实样本内容变化");
        List<Fold> folds=new ArrayList<>();
        for(int i=0;i<STARTS.size();i++) {
            LocalDate start=STARTS.get(i),end=ENDS.get(i);
            var train=dataset.samples().stream().filter(s->s.asOfDate().isBefore(start)&&s.targetDate().isBefore(start)).toList();
            var validation=dataset.samples().stream().filter(s->!s.asOfDate().isBefore(start)&&!s.asOfDate().isAfter(end)).toList();
            if(train.size()!=779+i*240||validation.size()!=240||!validation.getLast().targetDate().isBefore(TrainingProbe.LIMIT))
                throw new IllegalStateException("固定区间样本数或时间隔离变化");
            double[] frequencies=new double[3];for(var s:train)frequencies[s.label().ordinal()]+=1.0/train.size();
            GoldClassifier prior=f->new DirectionProbabilities(frequencies[0],frequencies[1],frequencies[2]);
            Map<FeatureProfile,SoftmaxFit.Fit> fits=new EnumMap<>(FeatureProfile.class);
            Map<FeatureProfile,FeatureScaler> scales=new EnumMap<>(FeatureProfile.class);
            // 先独立完成全部线性求解，再决定这一折能否评分，避免拿未收敛模型作弱参照。
            for(FeatureProfile profile:FeatureProfile.values()){
                var names=profile.featureNames().stream().sorted().toList();var scale=FeatureScaler.fit(train,names);scales.put(profile,scale);
                var fit=SoftmaxFit.fit(train.stream().map(s->scale.values(s.features())).toArray(double[][]::new),train.stream().mapToInt(s->s.label().ordinal()).toArray());
                fits.put(profile,fit);System.out.println(start+" "+profile+" "+fit.status()+" gradient="+fit.trace().getLast().gradient());
            }
            boolean converged=fits.values().stream().allMatch(SoftmaxFit.Fit::converged);List<Compare> comparisons=new ArrayList<>();
            for(FeatureProfile profile:FeatureProfile.values()) {
                var fit=fits.get(profile);
                if(!converged){comparisons.add(new Compare(profile,fit,null,null,null,null,null,List.of(),List.of(),0));continue;}
                var scale=scales.get(profile);
                GoldClassifier linear=f->{double[] p=SoftmaxFit.probabilities(fit.weights(),scale.values(f));return new DirectionProbabilities(p[0],p[1],p[2]);};
                var trainer=new XgboostGoldTrainer(TREE);GoldClassifier tree=trainer.train(train,profile.featureNames());
                GoldClassifier repeat=trainer.train(train,profile.featureNames());double max=0;
                List<Row> rows=new ArrayList<>();List<GoldSample> selected=new ArrayList<>();
                for(var s:validation){
                    double[] p=array(tree.predict(s.features())),q=array(repeat.predict(s.features()));
                    for(int c=0;c<3;c++)max=Math.max(max,Math.abs(p[c]-q[c]));
                    if(Arrays.stream(p).max().orElseThrow()>=.55)selected.add(s);
                    rows.add(new Row(s.asOfDate(),s.targetDate(),s.label().name(),array(linear.predict(s.features())),p,frequencies.clone()));
                }
                if(max>1e-12)throw new IllegalStateException("固定树模型重复训练未复现");
                comparisons.add(new Compare(profile,fit,score(linear,train),score(tree,train),score(linear,validation),score(tree,validation),
                        selected.isEmpty()?null:score(prior,selected).all(),selected.stream().map(GoldSample::asOfDate).toList(),rows,max));
                System.out.println(start+" "+profile+" linear="+comparisons.getLast().linearValidation().all().accuracy()+" tree="+comparisons.getLast().treeValidation().all().accuracy());
            }
            folds.add(new Fold(start,end,train.size(),train.getFirst().asOfDate(),train.getLast().targetDate(),validation.size(),
                    converged?score(prior,validation):null,comparisons,converged));
        }
        Map<String,Object> report=new LinkedHashMap<>();report.put("version","fred-fixed-nonlinear-v1");report.put("gitCommit",args[1]);report.put("createdAt",OffsetDateTime.now());
        report.put("barHash",barsHash);report.put("contentHash",contentHash);report.put("datasetHash",new GoldDatasetFingerprint().hash(dataset));report.put("samples",dataset.samples().size());
        report.put("macroInput",dataset.macroInput());report.put("treeParameters",TREE);report.put("promotionAllowed",false);report.put("cutoffExclusive",TrainingProbe.LIMIT);
        Map<String,String> hashes=new LinkedHashMap<>();for(String file:List.of("SoftmaxFit.java","SoftmaxChecks.java","MacroModelProbe.java","TrainingProbe.java"))
            hashes.put(file,TrainingProbe.sha(Files.readAllBytes(Path.of("../docs/research/probes/"+file))));
        report.put("sourceHashes",hashes);report.put("protocolHash",TrainingProbe.sha(Files.readAllBytes(Path.of("../docs/research/2026-10-02-macro-model-protocol.md"))));
        report.put("folds",folds);json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
        System.out.println("REPORT "+output+" allConverged="+folds.stream().allMatch(Fold::allConverged));
    }
    static double[] array(DirectionProbabilities p){return new double[]{p.bullish(),p.neutral(),p.bearish()};}
    static Scores score(GoldClassifier model,List<GoldSample> samples){
        List<SettledPrediction> all=new ArrayList<>(),signals=new ArrayList<>();
        for(var s:samples){var p=model.predict(s.features());double[] a=array(p);int best=a[0]>=a[1]&&a[0]>=a[2]?0:a[1]>=a[2]?1:2;
            var d=ForecastDirection.values()[best];var prediction=new GoldPrediction(SignalStatus.PREDICTED,d,a[best]);
            all.add(new SettledPrediction(s.asOfDate(),p,prediction,s.label()));
            signals.add(new SettledPrediction(s.asOfDate(),p,a[best]>=.55?prediction:new GoldPrediction(SignalStatus.NO_SIGNAL,null,a[best]),s.label()));}
        var evaluator=new ForecastEvaluator();return new Scores(evaluator.evaluate(all),evaluator.evaluate(signals));
    }
    static String contentHash(GoldDataset dataset)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(var s:dataset.samples()){
            for(String value:List.of(s.asOfDate().toString(),s.targetDate().toString(),s.label().name(),s.horizon().name()))add(digest,value);
            for(String name:s.features().values().keySet().stream().sorted().toList()){add(digest,name);add(digest,String.valueOf(s.features().values().get(name)));}
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static void add(MessageDigest digest,String value){digest.update(value.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}
}
