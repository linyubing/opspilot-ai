package com.opspilot.ai.forecast;

import com.opspilot.ai.analysis.history.GoldResearchSnapshotRepository;
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
            "gold-multifactor-v2";

    private final GoldResearchSnapshotRepository snapshotRepository;
    private final GoldForecastRepository forecastRepository;
    private final GoldForecastPromptBuilder promptBuilder;
    private final GoldForecastGateway gateway;
    private final GoldForecastValidator validator;
    private final GoldForecastDataFreshnessPolicy freshnessPolicy;
    private final String modelName;
    private final Clock clock;

    public GoldForecastGenerationService(
            GoldResearchSnapshotRepository snapshotRepository,
            GoldForecastRepository forecastRepository,
            GoldForecastPromptBuilder promptBuilder,
            GoldForecastGateway gateway,
            GoldForecastValidator validator,
            GoldForecastDataFreshnessPolicy freshnessPolicy,
            GoldForecastProperties properties,
            Clock clock
    ) {
        this.snapshotRepository = snapshotRepository;
        this.forecastRepository = forecastRepository;
        this.promptBuilder = promptBuilder;
        this.gateway = gateway;
        this.validator = validator;
        this.freshnessPolicy = freshnessPolicy;
        this.modelName = properties.modelName();
        this.clock = clock;
    }

    /**
     * 为指定的正式研究快照生成一条不可覆盖的黄金方向预测。
     *
     * @param snapshotId 正式研究快照编号
     * @return 数据库最终保留的预测，以及本次是否新建
     */
    public SaveGoldForecastResult generate(UUID snapshotId) {
        StoredGoldResearchSnapshot snapshot = requireSnapshot(snapshotId);

        validateSnapshot(snapshot);

        // 先检查幂等记录，避免重复调用大模型产生费用。
        return forecastRepository.findByKey(
                        snapshot.id(),
                        modelName,
                        GoldForecastPromptBuilder.PROMPT_VERSION,
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

        GoldForecastPrompt prompt = promptBuilder.build(snapshot);
        GeneratedGoldForecast generated = gateway.generate(prompt);

        // 模型返回内容必须先通过安全边界校验，才能写入数据库。
        validator.validate(generated.content());

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

                        OffsetDateTime.now(clock)
                );

        /*
         * 前面的查询主要用于避免重复调用模型；
         * 数据库唯一约束负责处理并发情况下的最终幂等。
         */
        return forecastRepository.saveIfAbsent(candidate);

    }

    /**
     * 旧版或单因子快照不能参与正式方向预测。
     */
    private void validateSnapshot(StoredGoldResearchSnapshot snapshot) {
        String researchVersion = snapshot.snapshot().researchVersion();

        if (!REQUIRED_RESEARCH_VERSION.equals(researchVersion)) {
            throw new InvalidGoldForecastSnapshotException(researchVersion);
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
