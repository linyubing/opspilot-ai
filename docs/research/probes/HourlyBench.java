package com.opspilot.ai.forecast.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.macrodata.FredHistoryStore;
import com.opspilot.ai.macrodata.MacroObservation;
import org.tribuo.Model;
import org.tribuo.MutableDataset;
import org.tribuo.classification.Label;
import org.tribuo.classification.LabelFactory;
import org.tribuo.classification.sgd.linear.LogisticRegressionTrainer;
import org.tribuo.impl.ArrayExample;
import org.tribuo.provenance.SimpleDataSourceProvenance;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 独立小时聚合研究评估；不连接数据库，不访问正式留出集。 */
public class HourlyBench {
    static final String[] CLASSES={"BULLISH","NEUTRAL","BEARISH"};
    static final String[] FEATURES={"return1","return5","return20","ma20Distance","dailyRange"};
    static final String[] RATE_FEATURES={"return1","return5","return20","ma20Distance","dailyRange","realRate","rateChange5"};
    record Sample(String baseEnd,String targetEnd,String label,double actualReturn,double[] values) {}
    record Score(String actual, String predicted, double[] probabilities) {}
    record Metrics(int samples, double accuracy, Double balancedAccuracy, double brier,
                   double logLoss, Double[] recalls) {}

    static Metrics metrics(List<Score> scores) {
        if (scores.isEmpty()) throw new IllegalArgumentException("No scored samples");
        int[] counts=new int[3], hits=new int[3];
        double brier=0, loss=0;
        int correct=0;
        for (Score score:scores) {
            int actual=classIndex(score.actual()), predicted=classIndex(score.predicted());
            var p=score.probabilities();
            if (p.length!=3) throw new IllegalArgumentException("Three probabilities required");
            double sum=0;
            for (double value:p) {
                if (!Double.isFinite(value) || value<0 || value>1) throw new IllegalArgumentException("Invalid probability");
                sum+=value;
            }
            if (Math.abs(sum-1)>1e-8) throw new IllegalArgumentException("Probability sum invalid");
            counts[actual]++;
            if (actual==predicted) { hits[actual]++; correct++; }
            for (int c=0;c<3;c++) brier+=Math.pow(p[c]-(c==actual?1:0),2);
            loss-=Math.log(Math.max(1e-15,p[actual]));
        }
        Double[] recalls=new Double[3];
        double balanced=0;
        boolean all=true;
        for (int c=0;c<3;c++) {
            if (counts[c]==0) { all=false; continue; }
            recalls[c]=(double)hits[c]/counts[c]; balanced+=recalls[c];
        }
        int n=scores.size();
        return new Metrics(n,(double)correct/n,all?balanced/3:null,brier/n,loss/n,recalls);
    }

    static int classIndex(String value) {
        return switch(value) { case "BULLISH"->0; case "NEUTRAL"->1; case "BEARISH"->2;
            default->throw new IllegalArgumentException("Unknown class"); };
    }

    // 严格隔离一条即时前样本：训练标签结束时间必须早于预测基准时刻。
    static int trainEnd(int index) { return index-1; }
    static int foldBase(int index,int first,int refitEvery) { return first+(index-first)/refitEvery*refitEvery; }

    record Scale(double[] means,double[] widths) {
        double[] apply(double[] values) {
            double[] result=new double[values.length];
            for (int c=0;c<values.length;c++) result[c]=widths[c]==0?0:(values[c]-means[c])/widths[c];
            return result;
        }
    }
    static Scale fit(List<double[]> values) {
        int columns=values.getFirst().length,count=0;
        double[] means=new double[columns], variance=new double[columns];
        // 只从本折训练输入计算Welford均值和总体标准差。
        for (var row:values) {
            count++;
            for(int c=0;c<columns;c++) {
                double delta=row[c]-means[c]; means[c]+=delta/count;
                variance[c]+=delta*(row[c]-means[c]);
            }
        }
        double[] widths=new double[columns];
        for(int c=0;c<columns;c++) widths[c]=Math.sqrt(Math.max(0,variance[c]/count));
        return new Scale(means,widths);
    }

    static String direction(double value) {
        return value>.5?CLASSES[0]:value<-.5?CLASSES[2]:CLASSES[1];
    }

    static double[] transition(List<Sample> training,double previousReturn) {
        String state=direction(previousReturn);
        // 固定Laplace平滑，不按验证结果调参；只使用相同前态的既往训练标签。
        double[] counts={1,1,1};
        double total=3;
        for(var sample:training) {
            if(direction(sample.values()[0]).equals(state)) { counts[classIndex(sample.label())]++; total++; }
        }
        for(int c=0;c<3;c++) counts[c]/=total;
        return counts;
    }

    static boolean canSettle(OffsetDateTime sourceChecked,OffsetDateTime targetEnd) {
        return !sourceChecked.isBefore(targetEnd);
    }

    static void settle(String forecastPath,String sourcePath,String outputPath) throws Exception {
        var mapper=new ObjectMapper();
        var forecast=mapper.readTree(Files.readString(Path.of(forecastPath)));
        var checked=mapper.readTree(Files.readString(Path.of(sourcePath+".check.json")));
        String receiptHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(sourcePath))));
        String basis="TWELVE_HOURLY_UTC21_RESEARCH_V1";
        if(!forecast.path("status").asText().equals("RESEARCH_ONLY")
                || !forecast.path("basis").asText().equals(basis) || !checked.path("basis").asText().equals(basis)
                || !checked.path("receiptSha256").asText().equals(receiptHash)
                || forecast.path("productionEligible").asBoolean(true) || checked.path("productionEligible").asBoolean(true)) {
            throw new IllegalArgumentException("Research forecast and source lineage required");
        }
        var baseEnd=OffsetDateTime.parse(forecast.path("baseEnd").asText());
        var targetEnd=OffsetDateTime.parse(forecast.path("targetEnd").asText());
        var created=OffsetDateTime.parse(forecast.path("createdAt").asText());
        var completed=OffsetDateTime.parse(forecast.path("completedAt").asText());
        if(!targetEnd.equals(baseEnd.plusDays(1)) || created.isAfter(completed) || !completed.isBefore(targetEnd)) {
            throw new IllegalArgumentException("Forecast was not locked before target end");
        }
        String predicted=forecast.path("forecast").path("direction").asText();
        classIndex(predicted);
        var savedBase=forecast.path("bars").get(0);
        if(savedBase==null || !OffsetDateTime.parse(savedBase.path("endExclusive").asText()).isEqual(baseEnd)) {
            throw new IllegalArgumentException("Saved baseline not aligned");
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("basis",basis); result.put("researchOnly",true); result.put("timingLayer",forecast.path("timingLayer").asText());
        result.put("forecastVersion",forecast.path("version").asText());
        result.put("trustedAccuracyEligible",false); result.put("productionEligible",false);
        result.put("forecastReceiptSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(forecastPath)))));
        result.put("sourceReceiptSha256",receiptHash); result.put("targetEnd",targetEnd.toString());
        if(!canSettle(OffsetDateTime.now(),targetEnd)) {
            result.put("status","WAITING_WINDOW_END");
        } else if(!canSettle(OffsetDateTime.parse(checked.path("checkedAt").asText()),targetEnd)) {
            result.put("status","SOURCE_NOT_READY");
        } else {
            com.fasterxml.jackson.databind.JsonNode base=null,target=null;
            for(var row:checked.path("bars")) {
                var end=OffsetDateTime.parse(row.path("endExclusive").asText());
                if(end.isEqual(baseEnd)) base=row;
                if(end.isEqual(targetEnd)) target=row;
            }
            if(base==null || target==null) result.put("status","MISSING_COMPLETE_WINDOW");
            else {
                boolean revised=false;
                for(String field:List.of("open","high","low","close")) {
                    if(new BigDecimal(base.path(field).asText()).compareTo(new BigDecimal(savedBase.path(field).asText()))!=0) revised=true;
                }
                if(revised) result.put("status","BASE_SOURCE_REVISION");
                else {
                    double actual=new BigDecimal(target.path("close").asText())
                            .divide(new BigDecimal(savedBase.path("close").asText()),MathContext.DECIMAL128)
                            .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).doubleValue();
                    result.put("status","RESEARCH_SETTLED"); result.put("predicted",predicted);
                    result.put("actual",direction(actual)); result.put("actualReturn",actual);
                    result.put("correct",predicted.equals(direction(actual)));
                }
            }
        }
        // 未结束、缺失或修订都没有correct字段，不能被统计成一次未命中。
        var json=mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        Files.writeString(Path.of(outputPath),json,java.nio.file.StandardOpenOption.CREATE_NEW);
        System.out.println(mapper.writeValueAsString(Map.of("status",result.get("status"),"trustedAccuracyEligible",false)));
    }

    static ArrayExample<Label> example(Label label,double[] values,Scale scale) {
        return new ArrayExample<>(label,FEATURES,scale.apply(values));
    }

    // 5个观测间隔的百分点变化，不把周末缺失补成新观测。
    static double[] rateValues(LocalDate day,List<MacroObservation> rows) {
        if(rows.size()!=6) throw new IllegalArgumentException("实际利率缺少6条已知观测，停止配对比较");
        LocalDate previous=day;
        for(var row:rows) {
            if(!row.seriesId().equals("DFII10") || !row.observationDate().isBefore(previous)
                    || !Double.isFinite(row.value().doubleValue())) throw new IllegalArgumentException("实际利率日期或值无效");
            previous=row.observationDate();
        }
        if(ChronoUnit.DAYS.between(rows.getFirst().observationDate(),day)>7) {
            throw new IllegalArgumentException("实际利率观测超期，停止配对比较");
        }
        return new double[]{rows.getFirst().value().doubleValue(),rows.getFirst().value().subtract(rows.getLast().value()).doubleValue()};
    }

    public static void main(String[] args) throws Exception {
        if(args.length==4 && args[0].equals("--settle")) { settle(args[1],args[2],args[3]); return; }
        if(args.length!=2 && args.length!=3) throw new IllegalArgumentException("Expected source receipt, output file and optional FRED archive");
        // 可选利率消融与旧价格协议并列运行，不更换旧控制组。
        FredHistoryStore.Batch macro=args.length==3?new FredHistoryStore(new ObjectMapper(),args[2]).load():null;
        Path receipt=Path.of(args[0]), output=Path.of(args[1]);
        if(Files.exists(output)) throw new IllegalArgumentException("Experiment output exists; no overwrite");
        ObjectMapper mapper=new ObjectMapper();
        var checked=mapper.readTree(Files.readString(Path.of(args[0]+".check.json")));
        String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(receipt)));
        if(!fingerprint.equals(checked.path("receiptSha256").asText())
                || !checked.path("basis").asText().equals("TWELVE_HOURLY_UTC21_RESEARCH_V1")
                || checked.path("productionEligible").asBoolean(true)
                || checked.path("officialSessionCertified").asBoolean(true)) {
            throw new IllegalArgumentException("Independent research lineage not verified");
        }
        int available=checked.path("consecutiveCompleteWindows").asInt();
        // 固定历史消融只接受预先冻结的两个真实回执；新批次必须另立协议。
        if(macro!=null && (!fingerprint.equals("5f2921bd1541c5aa114cd525e93349836cb50b7d298ec3523b67005966272439")
                || available!=186 || !macro.metadata().get("DFII10.sha256").equals("fe6ff2b7f3be1ceefd05d44123e7fcfca3b70715691e4abb9ad622e8dab9a62b"))) {
            throw new IllegalArgumentException("FROZEN_RATE_COHORT_MISMATCH: 实际利率消融的冻结数据批次不匹配");
        }
        if(available<132) throw new IllegalArgumentException("Not enough real continuous history for fixed protocol");
        var rows=checked.path("bars");
        var collected=OffsetDateTime.parse(checked.path("checkedAt").asText());
        List<GoldDailyBar> bars=new ArrayList<>();
        List<String> ends=new ArrayList<>();
        // 仅取最新连续段；类型仅用于复用纯特征公式，confirmation始终null，不写正式仓库。
        for(int i=available-1;i>=0;i--) {
            var row=rows.get(i);
            var end=OffsetDateTime.parse(row.path("endExclusive").asText());
            if(i<available-1 && !end.minusDays(1).toString().equals(OffsetDateTime.parse(ends.getLast()).toString())) {
                throw new IllegalArgumentException("Non-continuous windows");
            }
            ends.add(end.toString());
            bars.add(new GoldDailyBar("XAUUSD",end.toLocalDate(),new BigDecimal(row.path("open").asText()),
                    new BigDecimal(row.path("high").asText()),new BigDecimal(row.path("low").asText()),
                    new BigDecimal(row.path("close").asText()),"usd","troy_ounce","hourly_research_utc21",collected));
        }
        // 固定保留最近30个可标注样本；不计算这些位置的标签、特征或成绩。
        final int reserved=30, warmup=20, firstValidation=61, refitEvery=10;
        int devEnd=bars.size()-1-reserved;
        List<Sample> samples=new ArrayList<>();
        List<double[]> rateSamples=new ArrayList<>();
        List<Map<String,Object>> rateInputs=new ArrayList<>();
        var calculator=new GoldFeatureCalculator();
        for(int i=warmup;i<devEnd;i++) {
            var f=calculator.compute(bars.get(i).priceDate(),bars.subList(0,i+1)).orElseThrow();
            double[] values=Arrays.stream(FEATURES).mapToDouble(name->f.values().get(name)).toArray();
            double actual=bars.get(i+1).close().divide(bars.get(i).close(),MathContext.DECIMAL128)
                    .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).doubleValue();
            samples.add(new Sample(ends.get(i),ends.get(i+1),direction(actual),actual,values));
            if(macro!=null) {
                var day=bars.get(i).priceDate();
                var known=macro.recent("DFII10",day,6);
                var rate=rateValues(day,known);
                double[] combined=Arrays.copyOf(values,7); combined[5]=rate[0]; combined[6]=rate[1];
                rateSamples.add(combined);
                rateInputs.add(Map.of("baseDate",day.toString(),"latestDate",known.getFirst().observationDate().toString(),
                        "cutoff",day.minusDays(1).toString(),"level",rate[0],"change5",rate[1]));
            }
        }
        Map<String,List<Score>> scores=new LinkedHashMap<>();
        for(String name:List.of("ALWAYS_NEUTRAL","PAST_MAJORITY","RETURN1_CONTINUATION","LOGISTIC_5","TRANSITION_3")) scores.put(name,new ArrayList<>());
        if(macro!=null) scores.put("LOGISTIC_7",new ArrayList<>());
        List<Map<String,Object>> cases=new ArrayList<>(), folds=new ArrayList<>();
        var factory=new LabelFactory();
        Model<Label> model=null;
        Scale scale=null;
        Model<Label> rateModel=null;
        Scale rateScale=null;
        for(int i=firstValidation;i<samples.size();i++) {
            var sample=samples.get(i);
            // 所有可学习方法共享本折已冻结训练样本，不能让基线每天偷偷多看标签。
            var training=samples.subList(0,trainEnd(foldBase(i,firstValidation,refitEvery)));
            if(!OffsetDateTime.parse(training.getLast().targetEnd()).isBefore(OffsetDateTime.parse(sample.baseEnd()))) {
                throw new IllegalArgumentException("Future label in training");
            }
            if((i-firstValidation)%refitEvery==0) {
                scale=fit(training.stream().map(Sample::values).toList());
                var dataset=new MutableDataset<Label>(new SimpleDataSourceProvenance("hourly-independent-research",factory),factory);
                for(var train:training) dataset.add(example(new Label(train.label()),train.values(),scale));
                model=new LogisticRegressionTrainer().train(dataset);
                if(macro!=null) {
                    rateScale=fit(rateSamples.subList(0,training.size()));
                    var rateDataset=new MutableDataset<Label>(new SimpleDataSourceProvenance("hourly-rate-independent-research",factory),factory);
                    for(int t=0;t<training.size();t++) rateDataset.add(new ArrayExample<>(new Label(training.get(t).label()),RATE_FEATURES,rateScale.apply(rateSamples.get(t))));
                    rateModel=new LogisticRegressionTrainer().train(rateDataset);
                }
                folds.add(Map.of("validationBase",sample.baseEnd(),"trainingSamples",training.size(),
                        "latestTrainingTarget",training.getLast().targetEnd(),"purgedSamples",1));
            }
            double[] majority=new double[3];
            for(var train:training) majority[classIndex(train.label())]++;
            for(int c=0;c<3;c++) majority[c]/=training.size();
            var prediction=model.predict(example(LabelFactory.UNKNOWN_LABEL,sample.values(),scale));
            if(!prediction.hasProbabilities()) throw new IllegalStateException("Logistic probabilities unavailable");
            double[] logistic=new double[3];
            for(int c=0;c<3;c++) {
                var score=prediction.getOutputScores().get(CLASSES[c]);
                if(score==null) throw new IllegalStateException("Training lacks one class; experiment rejected");
                logistic[c]=score.getScore();
            }
            String continuation=direction(sample.values()[0]);
            double[] point=new double[3]; point[classIndex(continuation)]=1;
            var probs=new LinkedHashMap<String,double[]>();
            probs.put("ALWAYS_NEUTRAL",new double[]{0,1,0}); probs.put("PAST_MAJORITY",majority);
            probs.put("RETURN1_CONTINUATION",point); probs.put("LOGISTIC_5",logistic);
            probs.put("TRANSITION_3",transition(training,sample.values()[0]));
            if(macro!=null) {
                var p=rateModel.predict(new ArrayExample<>(LabelFactory.UNKNOWN_LABEL,RATE_FEATURES,rateScale.apply(rateSamples.get(i))));
                if(!p.hasProbabilities()) throw new IllegalStateException("实际利率模型没有三方向概率");
                double[] rateProb=new double[3];
                for(int c=0;c<3;c++) {
                    var s=p.getOutputScores().get(CLASSES[c]);
                    if(s==null) throw new IllegalStateException("实际利率训练折缺少类别");
                    rateProb[c]=s.getScore();
                }
                probs.put("LOGISTIC_7",rateProb);
            }
            var predictions=new LinkedHashMap<String,String>();
            for(var entry:probs.entrySet()) {
                int best=0; for(int c=1;c<3;c++) if(entry.getValue()[c]>entry.getValue()[best]) best=c;
                predictions.put(entry.getKey(),CLASSES[best]);
                scores.get(entry.getKey()).add(new Score(sample.label(),CLASSES[best],entry.getValue()));
            }
            cases.add(Map.of("baseEnd",sample.baseEnd(),"targetEnd",sample.targetEnd(),"actual",sample.label(),
                    "predictions",predictions,"actualReturn",sample.actualReturn()));
        }
        var metrics=new LinkedHashMap<String,Metrics>();
        scores.forEach((name,rowsScored)->metrics.put(name,metrics(rowsScored)));
        var pairs=new LinkedHashMap<String,Object>();
        var candidates=new ArrayList<>(List.of("LOGISTIC_5","TRANSITION_3"));
        if(macro!=null) candidates.add("LOGISTIC_7");
        for(String name:candidates) {
            var candidate=scores.get(name);
            var comparisons=new LinkedHashMap<String,Object>();
            var baselines=new ArrayList<>(List.of("ALWAYS_NEUTRAL","PAST_MAJORITY","RETURN1_CONTINUATION"));
            if(name.equals("LOGISTIC_7")) baselines.add("LOGISTIC_5");
            for(String baseline:baselines) {
                int better=0,worse=0;
                var reference=scores.get(baseline);
                for(int i=0;i<candidate.size();i++) {
                    boolean a=candidate.get(i).actual().equals(candidate.get(i).predicted());
                    boolean b=reference.get(i).actual().equals(reference.get(i).predicted());
                    if(a&&!b) better++; if(!a&&b) worse++;
                }
                comparisons.put(baseline,Map.of("candidateOnlyCorrect",better,"baselineOnlyCorrect",worse));
            }
            pairs.put(name,comparisons);
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("version",macro==null?"hourly-retrospective-fixed-protocol-v3-shared-folds":"hourly-price-rate-ablation-v1");
        result.put("basis",checked.path("basis").asText()); result.put("sourceReceiptSha256",fingerprint);
        result.put("collectedAt",collected.toString()); result.put("evaluationType","RETROSPECTIVE_RECONSTRUCTION");
        result.put("officialSessionCertified",false); result.put("productionEligible",false);
        result.put("inputVintageKnownAtHistoricalTime",false); result.put("trustedForwardAccuracyEligible",false);
        result.put("warmupWindows",21); result.put("completeWindows",available); result.put("developmentSamples",samples.size());
        result.put("reservedSamplePositions",reserved); result.put("reservedLabelsReadByBenchmark",false);
        // 这些位置只排除开发评分；先前前瞻输入及结算回归已涉及其中部分，不能冒充盲留出。
        result.put("reservedPurpose","EXCLUDED_FROM_DEVELOPMENT_SCORING_NOT_BLIND_HOLDOUT");
        result.put("validationStart",samples.get(firstValidation).baseEnd()); result.put("validationEnd",samples.getLast().baseEnd());
        result.put("featureNames",FEATURES); result.put("refitEvery",refitEvery); result.put("purge",1);
        if(macro!=null) {
            result.put("rateFeatureNames",RATE_FEATURES); result.put("macroInput",macro.metadata());
            result.put("rateInputs",rateInputs); result.put("macroVintagePolicy","fred-known-before-day-v1");
            // 记录实际解析器字节码，不把归档文件摘要冒充运行代码版本。
            var macroClasses=new LinkedHashMap<String,String>();
            for(String name:List.of("FredHistory","FredHistoryStore")) {
                var type=Class.forName("com.opspilot.ai.macrodata."+name);
                try(var stream=type.getResourceAsStream(name+".class")) {
                    if(stream==null) throw new IllegalStateException("无法核验历史版本解析器");
                    macroClasses.put(name,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())));
                }
            }
            result.put("macroClassSha256",macroClasses);
        }
        result.put("transitionSmoothing",1); result.put("thresholdPercent",.5); result.put("coverage",1);
        result.put("probeSourceSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(Path.of("docs/research/probes/HourlyBench.java")))));
        result.put("featureSourceSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(Path.of("backend/src/main/java/com/opspilot/ai/forecast/learning/GoldFeatureCalculator.java")))));
        // 同时记录实际加载的字节码，不能只用源码摘要证明运行版本。
        try (var compiled = GoldFeatureCalculator.class.getResourceAsStream("GoldFeatureCalculator.class")) {
            if (compiled == null) throw new IllegalStateException("无法核验特征计算字节码");
            result.put("featureClassSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(compiled.readAllBytes())));
        }
        result.put("trainer",new LogisticRegressionTrainer().getProvenance().toString());
        result.put("metrics",metrics); result.put("paired",pairs); result.put("folds",folds); result.put("cases",cases);
        Files.writeString(output,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result),java.nio.file.StandardOpenOption.CREATE_NEW);
        // 标准输出不展示原始价格或每条收益率。
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("metrics",metrics,"paired",pairs,
                "completeWindows",available,"validationSamples",scores.get("LOGISTIC_5").size(),"reservedLabelsReadByBenchmark",false)));
    }
}
