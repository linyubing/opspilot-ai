package com.opspilot.ai.forecast;

import com.opspilot.ai.analysis.history.GoldResearchSnapshotRepository;
import com.opspilot.ai.analysis.GoldResearchSnapshotService;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import com.opspilot.ai.analysis.narrative.GoldResearchSnapshotNotFoundException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 编排正式快照校验、模型预测、安全校验和幂等保存。
 */
@Service
public class GoldForecastGenerationService {

    private static final String REQUIRED_RESEARCH_VERSION =
            GoldResearchSnapshotService.RESEARCH_VERSION;

    private final GoldResearchSnapshotRepository snapshotRepository;
    private final GoldForecastRepository forecastRepository;
    private final GoldForecastPromptBuilder promptBuilder;
    private final GoldForecastGateway gateway;
    private final GoldForecastValidator validator;
    private final GoldForecastDataFreshnessPolicy freshnessPolicy;
    private final String modelName;
    private final Clock clock;
    private final GoldForecastPublicationPolicy publication;
    private final GoldBaseCheck baseCheck;
    private final boolean requireRun;

    public GoldForecastGenerationService(
            GoldResearchSnapshotRepository snapshotRepository,
            GoldForecastRepository forecastRepository,
            GoldForecastPromptBuilder promptBuilder,
            GoldForecastGateway gateway,
            GoldForecastValidator validator,
            GoldForecastDataFreshnessPolicy freshnessPolicy,
            GoldForecastProperties properties,
            Clock clock,
            GoldForecastPublicationPolicy publication,
            GoldBaseCheck baseCheck
    ) {
        this.snapshotRepository = snapshotRepository;
        this.forecastRepository = forecastRepository;
        this.promptBuilder = promptBuilder;
        this.gateway = gateway;
        this.validator = validator;
        this.freshnessPolicy = freshnessPolicy;
        this.modelName = properties.modelName();
        this.clock = clock;
        this.publication = publication;
        this.baseCheck = baseCheck;
        this.requireRun = properties.requireRun();
    }

    /**
     * 为指定的正式研究快照生成一条不可覆盖的黄金方向预测。
     *
     * @param snapshotId 正式研究快照编号
     * @return 数据库最终保留的预测，以及本次是否新建
     */
    public SaveGoldForecastResult generate(UUID snapshotId) {
        StoredGoldResearchSnapshot snapshot = requireSnapshot(snapshotId);

        validateSnapshot(snapshot, OffsetDateTime.now(clock));

        // 先检查幂等记录，避免重复调用大模型产生费用。
        return forecastRepository.findByKey(
                        snapshot.id(),
                        modelName,
                        GoldForecastPromptBuilder.FORMAL_VERSION,
                        GoldForecastRule.RULE_VERSION
                ).map(existing -> new SaveGoldForecastResult(existing, false))
                .orElseGet(() -> generateAndSave(snapshot));
    }

    /**
     * 只有不存在相同版本的预测时，才调用模型并保存结果。
     */
    private SaveGoldForecastResult generateAndSave(StoredGoldResearchSnapshot snapshot) {
        // 新鲜度只约束新预测，已经保存的历史预测仍可按幂等键读取。
        freshnessPolicy.validate(snapshot.snapshot());
        // 仅约束新生成；旧预测仍由前面的幂等查询原样返回，不补造宏观依据。
        var value = snapshot.snapshot();
        var input = value.input();
        if (input.macro() == null || !input.macro().matches(value, input.checkedAt())) {
            throw new InvalidGoldForecastSnapshotException(value.researchVersion(),
                    "缺少与冻结时刻及摘要匹配的宏观原始输入");
        }
        var timing = publication.plan(snapshot.snapshot().latestGoldDate());
        publication.check(snapshot.snapshot().latestGoldDate(), timing, clock);

        var baseProof = baseCheck.check(snapshot);
        // 行情核验也可能耗时；过期后不能继续调用模型。
        publication.check(snapshot.snapshot().latestGoldDate(), timing, clock);

        GoldForecastPrompt prompt = promptBuilder.build(snapshot, timing);
        GeneratedGoldForecast generated = gateway.generate(prompt);

        if (!modelName.equals(generated.modelName()) || !modelName.equals(generated.reportedModel())) {
            throw new InvalidGoldForecastAiResponseException("正式预测响应模型身份未核对，未保存结果", null);
        }

        var run = generated.modelRun();
        if ((requireRun && run == null) || (run != null && (!modelName.equals(run.model())
                || !prompt.sha256().equals(run.promptHash())
                || run.checkedBefore().isBefore(baseProof.checkedAt())
                || run.checkedAfter().isAfter(clock.instant())))) {
            throw new InvalidGoldForecastAiResponseException("逐次模型运行依据缺失或未绑定本次预测", null);
        }

        // 模型返回内容必须先通过安全边界校验，才能写入数据库。
        validator.validateFormal(generated.content());

        // 模型耗时可能跨过截止线，最终核验与createdAt使用同一绝对时刻。
        OffsetDateTime publishedAt = publication.check(snapshot.snapshot().latestGoldDate(), timing, clock);

        GoldDirectionForecastContent content = generated.content();
        StoredGoldDirectionForecast candidate =
                new StoredGoldDirectionForecast(
                        UUID.randomUUID(),
                        snapshot.id(),
                        snapshot.snapshot().latestGoldDate(),
                        snapshot.snapshot().gold().currentPrice(),
                        content.direction(),
                        content.reasoning(),
                        content.invalidationConditions(),
                        generated.modelName(),
                        prompt.version(),
                        prompt.sha256(),
                        GoldForecastRule.RULE_VERSION,
                        generated.rawResponse(),
                        ForecastStatus.PENDING,

                        // 下面这些字段需要后续真实行情才能确定。
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,

                        publishedAt,
                        timing,
                        content.signal(),
                        generated.reportedModel(),
                        generated.modelRun()
                );

        /*
         * 前面的查询主要用于避免重复调用模型；
         * 数据库唯一约束负责处理并发情况下的最终幂等。
         */
        var receipt = new GoldResolutionCheck(UUID.randomUUID(), candidate.id(),
                OffsetDateTime.ofInstant(baseProof.checkedAt(), java.time.ZoneOffset.UTC),
                GoldResolutionCheck.Code.GENERATION_BASE_CHECKED, baseProof, null);
        return forecastRepository.saveWithCheck(candidate, receipt);

    }

    /**
     * 旧版或单因子快照不能参与正式方向预测。
     */
    private void validateSnapshot(StoredGoldResearchSnapshot snapshot, OffsetDateTime asOf) {
        String researchVersion = snapshot.snapshot().researchVersion();

        if (!REQUIRED_RESEARCH_VERSION.equals(researchVersion)) {
            throw new InvalidGoldForecastSnapshotException(researchVersion);
        }
        // 必须证明保存快照时已经核验，不能用后来补得的依据升级旧快照。
        var value = snapshot.snapshot();
        if (snapshot.createdAt() == null || snapshot.createdAt().isAfter(asOf)
                || value.input() == null || !value.input().matches(
                        value.latestGoldDate(), value.gold(), snapshot.createdAt())) {
            throw new InvalidGoldForecastSnapshotException(researchVersion,
                    "缺少与保存时刻及黄金指标匹配的窗口确认依据");
        }
    }

    /**
     * 查询正式研究快照，不存在时给出明确的业务异常。
     */
    private StoredGoldResearchSnapshot requireSnapshot(UUID snapshotId) {
        Objects.requireNonNull(snapshotId, "快照编号不能为空");

        return snapshotRepository.findById(snapshotId)
                .orElseThrow(() ->
                        new GoldResearchSnapshotNotFoundException(snapshotId));
    }
}
