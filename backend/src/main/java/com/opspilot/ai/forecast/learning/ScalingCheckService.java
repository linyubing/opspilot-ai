package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 在一次固定时间分区中比较原始量纲和标准化逻辑回归，不调整阈值或正式模型。 */
@Service
public class ScalingCheckService {
    private final GoldDatasetBuilder builder;
    private final TemporalSplitter splitter;
    private final GoldDatasetFingerprint fingerprint;
    private final WalkForwardService walkForward;
    private final GitCommitProvider git;
    private final Clock clock;
    private final ForecastEvaluator evaluator;

    public ScalingCheckService(GoldDatasetBuilder builder, TemporalSplitter splitter,
            GoldDatasetFingerprint fingerprint, WalkForwardService walkForward,
            GitCommitProvider git, Clock clock, ForecastEvaluator evaluator) {
        this.builder = builder;
        this.splitter = splitter;
        this.fingerprint = fingerprint;
        this.walkForward = walkForward;
        this.git = git;
        this.clock = clock;
        this.evaluator = evaluator;
    }

    public ScalingReport run(ForecastHorizon horizon) {
        String commit = git.getRequired();
        GoldDataset dataset = builder.build(horizon);
        TemporalDataset split = splitter.split(dataset.samples(), horizon);
        GoldTrainer raw = new TribuoGoldTrainer(false);
        GoldTrainer scaled = new TribuoGoldTrainer(true);
        GoldTrainer majority = new MajorityGoldTrainer();
        List<ScalingReport.Result> rows = new ArrayList<>();
        for (FeatureProfile profile : FeatureProfile.values()) {
            List<SettledPrediction> before = walkForward.predict(split, profile, raw);
            List<SettledPrediction> after = walkForward.predict(split, profile, scaled);
            List<SettledPrediction> baseline = walkForward.predict(split, profile, majority);
            rows.add(compare(profile, before, after, baseline));
        }
        return new ScalingReport(UUID.randomUUID(), OffsetDateTime.now(clock), commit,
                fingerprint.hash(dataset), horizon,
                split.validation().getFirst().asOfDate(), split.validation().getLast().asOfDate(),
                split.finalHoldout().getFirst().asOfDate(), split.finalHoldout().getLast().asOfDate(),
                split.validation().size(), dataset.skippedCount(), WalkForwardService.CONFIDENCE, rows);
    }

    ScalingReport.Result compare(FeatureProfile profile, List<SettledPrediction> raw,
            List<SettledPrediction> scaled, List<SettledPrediction> majority) {
        return compare(profile, raw, scaled, majority, "logistic-v1", TribuoGoldTrainer.VERSION);
    }

    private ScalingReport.Result compare(FeatureProfile profile, List<SettledPrediction> raw,
            List<SettledPrediction> scaled, List<SettledPrediction> majority,
            String beforeVersion, String afterVersion) {
        if (raw.isEmpty() || raw.size() != scaled.size() || raw.size() != majority.size()) {
            throw new IllegalArgumentException("对照实验必须使用相同的非空样本集合");
        }
        for (int i = 0; i < raw.size(); i++) {
            if (!raw.get(i).asOfDate().equals(scaled.get(i).asOfDate())
                    || !raw.get(i).asOfDate().equals(majority.get(i).asOfDate())
                    || raw.get(i).actual() != scaled.get(i).actual()
                    || raw.get(i).actual() != majority.get(i).actual()) {
                throw new IllegalArgumentException("对照实验的日期或真实标签不一致");
            }
        }
        List<SettledPrediction> rawAll = all(raw);
        List<SettledPrediction> scaledAll = all(scaled);
        List<SettledPrediction> majorityAll = all(majority);
        List<SettledPrediction> rawBase = new ArrayList<>();
        List<SettledPrediction> scaledBase = new ArrayList<>();
        int common = 0, rawHits = 0, scaledHits = 0, scaledOnly = 0, rawOnly = 0;
        for (int i = 0; i < raw.size(); i++) {
            boolean oldSignal = raw.get(i).prediction().status() == SignalStatus.PREDICTED;
            boolean newSignal = scaled.get(i).prediction().status() == SignalStatus.PREDICTED;
            if (oldSignal) rawBase.add(majorityAll.get(i));
            if (newSignal) scaledBase.add(majorityAll.get(i));
            if (oldSignal && newSignal) {
                common++;
                rawHits += hit(raw.get(i));
                scaledHits += hit(scaled.get(i));
            }
            int oldHit = hit(rawAll.get(i)), newHit = hit(scaledAll.get(i));
            scaledOnly += newHit > oldHit ? 1 : 0;
            rawOnly += oldHit > newHit ? 1 : 0;
        }
        List<ScalingReport.Block> blocks = new ArrayList<>();
        for (int start = 0; start < raw.size(); start += WalkForwardService.REFIT_EVERY) {
            int end = Math.min(start + WalkForwardService.REFIT_EVERY, raw.size());
            blocks.add(new ScalingReport.Block(raw.get(start).asOfDate(), raw.get(end - 1).asOfDate(),
                    end - start, hits(rawAll.subList(start, end)), hits(scaledAll.subList(start, end)),
                    hits(majorityAll.subList(start, end))));
        }
        return new ScalingReport.Result(profile,
                score(beforeVersion, rawAll, raw),
                score(afterVersion, scaledAll, scaled),
                score("majority-v1", majorityAll, majority),
                rawBase.isEmpty() ? null : evaluator.evaluate(rawBase),
                scaledBase.isEmpty() ? null : evaluator.evaluate(scaledBase),
                new ScalingReport.Pair(common, rawHits, scaledHits, scaledOnly, rawOnly), blocks);
    }

    /** 窗口在看本轮结果前固定为 252/504 条；只检验下一交易日，不扩展调参网格。 */
    public WindowReport windows() {
        String commit = git.getRequired();
        ForecastHorizon horizon = ForecastHorizon.NEXT_DAY;
        GoldDataset dataset = builder.build(horizon);
        TemporalDataset split = splitter.split(dataset.samples(), horizon);
        List<WindowReport.Result> rows = new ArrayList<>();
        for (FeatureProfile profile : FeatureProfile.values()) {
            GoldTrainer fullTrainer = new TribuoGoldTrainer(true);
            List<SettledPrediction> full = walkForward.predict(split, profile, fullTrainer);
            List<SettledPrediction> baseline = walkForward.predict(split, profile, new MajorityGoldTrainer());
            for (int size : List.of(252, 504)) {
                GoldTrainer recentTrainer = new WindowGoldTrainer(new TribuoGoldTrainer(true), size);
                List<SettledPrediction> recent = walkForward.predict(split, profile, recentTrainer);
                rows.add(WindowReport.Result.from(size, compare(profile, full, recent, baseline,
                        fullTrainer.name(), recentTrainer.name())));
            }
        }
        return new WindowReport(UUID.randomUUID(), OffsetDateTime.now(clock), commit,
                fingerprint.hash(dataset), horizon,
                split.validation().getFirst().asOfDate(), split.validation().getLast().asOfDate(),
                split.finalHoldout().getFirst().asOfDate(), split.finalHoldout().getLast().asOfDate(),
                split.validation().size(), dataset.skippedCount(), WalkForwardService.CONFIDENCE, rows);
    }

    private ScalingReport.Score score(String version, List<SettledPrediction> all,
            List<SettledPrediction> rows) {
        List<SettledPrediction> selected = rows.stream()
                .filter(row -> row.prediction().status() == SignalStatus.PREDICTED).toList();
        // 原有 signals 中概率误差覆盖全部样本；selectedDates 则让概率误差也按信号日期对齐。
        return new ScalingReport.Score(version, evaluator.evaluate(all), evaluator.evaluate(rows),
                selected.isEmpty() ? null : evaluator.evaluate(selected));
    }

    private List<SettledPrediction> all(List<SettledPrediction> source) {
        return source.stream().map(row -> {
            DirectionProbabilities p = row.probabilities();
            // 仅用于完整样本评分；并列时按上涨、中性、下跌的固定顺序决定，绝不据真实标签择优。
            ForecastDirection direction = p.bullish() >= p.neutral() && p.bullish() >= p.bearish()
                    ? ForecastDirection.BULLISH : p.neutral() >= p.bearish()
                    ? ForecastDirection.NEUTRAL : ForecastDirection.BEARISH;
            double confidence = Math.max(p.bullish(), Math.max(p.neutral(), p.bearish()));
            return new SettledPrediction(row.asOfDate(), p,
                    new GoldPrediction(SignalStatus.PREDICTED, direction, confidence), row.actual());
        }).toList();
    }

    private int hit(SettledPrediction row) { return row.actual() == row.prediction().direction() ? 1 : 0; }

    private int hits(List<SettledPrediction> rows) { return rows.stream().mapToInt(this::hit).sum(); }
}
