package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.macrodata.CpiValues;
import com.opspilot.ai.forecast.ForecastDirection;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** 固定真实人群的单列通胀增量诊断；不注册正式模型、不晋级。 */
public class CpiCompare {
    record Group(ForecastMetrics model, ForecastMetrics prior, ForecastMetrics signals, List<CalibrationProbe.Row> rows) {}
    record Variant(double cpiMean, double cpiScale, SoftmaxFit.Fit solver, Group fit, Group diagnostic) {}
    record Fold(int fitCount, LocalDate diagnosticStart, Variant base, Variant cpi) {}
    public static void main(String[] args) throws Exception {
        if (args.length != 5 || !args[4].matches("[0-9a-f]{40}")) throw new IllegalArgumentException("需输入、CPI归档、原对照、新输出、提交");
        Path output = Path.of(args[3]);
        if (Files.exists(output)) throw new IllegalStateException("不能覆盖实验");
        String raw = Files.readString(Path.of(args[0]));
        var data = PriorCompare.JSON.readValue(raw, GoldDataset.class);
        if (!PriorCompare.HASH.equals(new GoldDatasetFingerprint().hash(data))) throw new IllegalStateException("数据集变化");
        if (data.samples().stream().anyMatch(s -> !s.targetDate().isBefore(PriorCompare.LIMIT))) throw new IllegalStateException("禁止封闭期");
        var old = PriorCompare.JSON.readTree(Path.of(args[2]).toFile());
        var values = CpiValues.read(Path.of(args[1]), data.samples().stream().map(GoldSample::asOfDate).toList());
        var names = FeatureProfile.ALL_36.featureNames().stream().sorted().toList();
        var folds = new ArrayList<Fold>();
        for (int k = 0; k < 3; k++) {
            var outer = PriorCompare.training(data.samples(), 548 + 240*k);
            var diagnostic = outer.subList(outer.size()-120, outer.size());
            var fit = outer.subList(0, outer.size()-120).stream()
                    .filter(s -> s.targetDate().isBefore(diagnostic.getFirst().asOfDate())).toList();
            if (fit.size()!=426+240*k) throw new IllegalStateException("训练范围变化");
            var counts=PriorCompare.counts(fit);
            var prior=new DirectionProbabilities((double)counts.get(ForecastDirection.BULLISH)/fit.size(),
                    (double)counts.get(ForecastDirection.NEUTRAL)/fit.size(),(double)counts.get(ForecastDirection.BEARISH)/fit.size());
            var scale=FeatureScaler.fit(fit,names);
            double mean=fit.stream().mapToDouble(s -> values.get(s.asOfDate())).average().orElseThrow();
            double sd=Math.sqrt(fit.stream().mapToDouble(s -> Math.pow(values.get(s.asOfDate())-mean,2)).sum()/fit.size());
            var base=run(fit,diagnostic,scale,values,prior,false,mean,sd);
            var cpi=run(fit,diagnostic,scale,values,prior,true,mean,sd);
            var archived=old.path("folds").get(k).path("diagnostic").path("rows");
            for(int i=0;i<120;i++) if(!PriorCompare.JSON.valueToTree(base.diagnostic.rows.get(i)).equals(archived.get(i)))
                throw new IllegalStateException("原36维概率未精确复现");
            folds.add(new Fold(fit.size(),diagnostic.getFirst().asOfDate(),base,cpi));
            System.out.println("折="+(k+1)+" 原="+base.diagnostic.model.accuracy()+" CPI="+cpi.diagnostic.model.accuracy()+" 频率="+cpi.diagnostic.prior.accuracy());
        }
        var report=new LinkedHashMap<String,Object>();
        report.put("gitCommit",args[4]);report.put("inputHash",PriorCompare.sha(raw));
        report.put("cpiArchiveHash",PriorCompare.sha(Files.readString(Path.of(args[1]))));
        report.put("oldHash",PriorCompare.sha(Files.readString(Path.of(args[2]))));
        report.put("featureNames",names);report.put("holdoutRead",false);report.put("promotionAllowed",false);
        report.put("cpiValues",new TreeMap<>(values));report.put("folds",folds);
        Files.writeString(output,PriorCompare.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)+"\n",StandardOpenOption.CREATE_NEW);
    }
    static double[] vector(GoldSample s,FeatureScaler scale,Map<LocalDate,Double> values,boolean extra,double mean,double sd) {
        double[] base=scale.values(s.features());
        if(!extra)return base;
        double[] x=Arrays.copyOf(base,37);x[36]=sd==0?0:(values.get(s.asOfDate())-mean)/sd;return x;
    }
    static Variant run(List<GoldSample> fit,List<GoldSample> diagnostic,FeatureScaler scale,
            Map<LocalDate,Double> values,DirectionProbabilities prior,boolean extra,double mean,double sd) {
        var solver=SoftmaxFit.fit(fit.stream().map(s->vector(s,scale,values,extra,mean,sd)).toArray(double[][]::new),
                fit.stream().mapToInt(s->s.label().ordinal()).toArray());
        if(!solver.converged())throw new IllegalStateException("固定求解未收敛："+solver.status());
        return new Variant(mean,sd,solver,group(fit,scale,values,prior,extra,mean,sd,solver),group(diagnostic,scale,values,prior,extra,mean,sd,solver));
    }
    static Group group(List<GoldSample> samples,FeatureScaler scale,Map<LocalDate,Double> values,
            DirectionProbabilities prior,boolean extra,double mean,double sd,SoftmaxFit.Fit solver) {
        var rows=new ArrayList<CalibrationProbe.Row>();var all=new ArrayList<SettledPrediction>();
        var baselines=new ArrayList<SettledPrediction>();var signals=new ArrayList<SettledPrediction>();
        for(var s:samples) {
            double[] p=SoftmaxFit.probabilities(solver.weights(),vector(s,scale,values,extra,mean,sd));
            var probs=new DirectionProbabilities(p[0],p[1],p[2]);
            rows.add(new CalibrationProbe.Row(s.asOfDate(),s.targetDate(),s.label(),probs,prior));
            all.add(PriorCompare.settle(s,probs,false));baselines.add(PriorCompare.settle(s,prior,false));signals.add(PriorCompare.settle(s,probs,true));
        }
        var evaluator=new ForecastEvaluator();
        return new Group(evaluator.evaluate(all),evaluator.evaluate(baselines),evaluator.evaluate(signals),rows);
    }
}
