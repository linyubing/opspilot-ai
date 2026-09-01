package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldResearchSnapshotService;
import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.GeneratedGoldForecast;
import com.opspilot.ai.forecast.GoldForecastAiUnavailableException;
import com.opspilot.ai.forecast.GoldForecastGateway;
import com.opspilot.ai.forecast.GoldForecastPrompt;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.forecast.GoldForecastValidator;
import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.GoldDailyBarRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 顺序执行黄金历史预测，并使用下一有效交易日价格结算。 */
@Service
public class BacktestRunner {

    private static final String SYMBOL = "XAUUSD";
    private static final String PROVIDER = "twelve_data";

    private final BacktestRepository repo;
    private final GoldDailyBarRepository barRepo;
    private final GoldResearchSnapshotService snapshotService;
    private final BacktestPromptBuilder baseBuilder;
    private final CandidateBacktestPromptBuilder candidateBuilder;
    private final ImprovedBacktestPromptBuilder improvedBuilder;
    private final CalibratedBacktestPromptBuilder calibratedBuilder;
    private final GoldForecastGateway gateway;
    private final GoldForecastValidator validator;
    private final GoldForecastRule rule;
    private final Clock clock;

    public BacktestRunner(
            BacktestRepository repo,
            GoldDailyBarRepository barRepo,
            GoldResearchSnapshotService snapshotService,
            BacktestPromptBuilder baseBuilder,
            CandidateBacktestPromptBuilder candidateBuilder,
            ImprovedBacktestPromptBuilder improvedBuilder,
            CalibratedBacktestPromptBuilder calibratedBuilder,
            GoldForecastGateway gateway,
            GoldForecastValidator validator,
            GoldForecastRule rule,
            Clock clock
    ) {
        this.repo = repo;
        this.barRepo = barRepo;
        this.snapshotService = snapshotService;
        this.baseBuilder = baseBuilder;
        this.candidateBuilder = candidateBuilder;
        this.improvedBuilder = improvedBuilder;
        this.calibratedBuilder = calibratedBuilder;
        this.gateway = gateway;
        this.validator = validator;
        this.rule = rule;
        this.clock = clock;
    }

    public void run(UUID id) {
        BacktestTask task = repo.findTask(id).orElseThrow(() ->
                new BacktestNotFoundException("回测任务不存在，编号=" + id)
        );
        List<LocalDate> dates = repo.findSampleDates(id);
        if (dates.size() != task.sampleCount()) {
            repo.fail(id, "冻结样本计划不完整，期望=" + task.sampleCount()
                    + "，实际=" + dates.size());
            return;
        }
        Set<LocalDate> done = repo.findDoneDates(id);

        for (LocalDate date : dates) {
            if (done.contains(date)) {
                continue;
            }

            try {
                repo.saveCase(runOne(task, date));
            } catch (GoldForecastAiUnavailableException exception) {
                repo.fail(id, safeMessage(exception));
                return;
            } catch (RuntimeException exception) {
                repo.recordFailure(id, safeMessage(exception));
            }
        }

        repo.complete(id, now());
    }

    private BacktestCase runOne(BacktestTask task, LocalDate date) {
        UUID caseId = UUID.randomUUID();
        GoldResearchSnapshot snapshot = snapshotService.createSnapshot(date);
        validateTimeConstraints(snapshot, date);

        GoldForecastPrompt prompt = buildPrompt(task, caseId, snapshot);
        GeneratedGoldForecast generated = gateway.generate(prompt);
        validator.validate(generated.content());

        GoldDailyBar nextBar = barRepo.findNext(
                SYMBOL,
                PROVIDER,
                date
        ).orElseThrow(() -> new BacktestDataInsufficientException(
                "回测日期之后没有可结算的真实价格，日期=" + date
        ));

        if (!nextBar.priceDate().isAfter(date)) {
            throw new BacktestDataInsufficientException(
                    "结算日必须晚于回测日期，结算日=" + nextBar.priceDate()
                            + "，回测日期=" + date
            );
        }

        BigDecimal basePrice = snapshot.gold().currentPrice();
        BigDecimal targetClose = nextBar.close();
        BigDecimal actualReturn = targetClose
                .subtract(basePrice)
                .divide(basePrice, 8, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(6, RoundingMode.HALF_UP);
        ForecastDirection actual = rule.classify(actualReturn);
        boolean hit = generated.content().direction() == actual;

        return new BacktestCase(
                caseId,
                task.id(),
                date,
                snapshot,
                basePrice,
                generated.content().direction(),
                generated.content().reasoning(),
                generated.content().invalidationConditions(),
                nextBar.priceDate(),
                targetClose,
                actualReturn,
                actual,
                hit,
                generated.modelName(),
                prompt.version(),
                prompt.sha256(),
                task.ruleVersion(),
                generated.rawResponse(),
                now()
        );
    }

    /** 验证快照时间约束，确保不会使用未来数据。 */
    private void validateTimeConstraints(GoldResearchSnapshot snapshot, LocalDate caseDate) {
        if (!snapshot.analysisDate().equals(caseDate)) {
            throw new BacktestDataInsufficientException(
                    "快照分析日期必须等于回测日期，analysisDate="
                            + snapshot.analysisDate() + "，caseDate=" + caseDate
            );
        }
        if (snapshot.latestGoldDate() != null
                && snapshot.latestGoldDate().isAfter(caseDate)) {
            throw new BacktestDataInsufficientException(
                    "黄金数据日期不得晚于回测日期，latestGoldDate="
                            + snapshot.latestGoldDate() + "，caseDate=" + caseDate
            );
        }
        if (snapshot.latestRealRateDate() != null
                && snapshot.latestRealRateDate().isAfter(caseDate)) {
            throw new BacktestDataInsufficientException(
                    "实际利率数据日期不得晚于回测日期，latestRealRateDate="
                            + snapshot.latestRealRateDate() + "，caseDate=" + caseDate
            );
        }
        if (snapshot.latestDollarIndexDate() != null
                && snapshot.latestDollarIndexDate().isAfter(caseDate)) {
            throw new BacktestDataInsufficientException(
                    "美元指数数据日期不得晚于回测日期，latestDollarIndexDate="
                            + snapshot.latestDollarIndexDate() + "，caseDate=" + caseDate
            );
        }
    }

    /** 按任务冻结的版本选择提示词，避免运行期间发生版本漂移。 */
    private GoldForecastPrompt buildPrompt(
            BacktestTask task,
            UUID caseId,
            GoldResearchSnapshot snapshot
    ) {
        if (CandidateBacktestPromptBuilder.VERSION.equals(task.promptVersion())) {
            return candidateBuilder.build(caseId, snapshot);
        }
        if (ImprovedBacktestPromptBuilder.VERSION.equals(task.promptVersion())) {
            return improvedBuilder.build(caseId, snapshot);
        }
        if (CalibratedBacktestPromptBuilder.VERSION.equals(task.promptVersion())) {
            return calibratedBuilder.build(caseId, snapshot);
        }
        if (BacktestPromptBuilder.VERSION.equals(task.promptVersion())) {
            return baseBuilder.build(caseId, snapshot);
        }
        throw new InvalidBacktestRequestException(
                "不支持的回测提示词版本=" + task.promptVersion()
        );
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
