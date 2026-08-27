package com.opspilot.ai.analysis.narrative;

import com.opspilot.ai.analysis.history.GoldResearchSnapshotRepository;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 编排正式快照查询、模型解读、安全校验和幂等留痕。 */
@Service
public class GoldResearchNarrativeService {

    private final GoldResearchSnapshotRepository snapshotRepository;
    private final ResearchNarrativeRepository narrativeRepository;
    private final ResearchNarrativePromptBuilder promptBuilder;
    private final ResearchNarrativeGateway gateway;
    private final ResearchNarrativeValidator validator;
    private final String modelName;
    private final Clock clock;

    public GoldResearchNarrativeService(
            GoldResearchSnapshotRepository snapshotRepository,
            ResearchNarrativeRepository narrativeRepository,
            ResearchNarrativePromptBuilder promptBuilder,
            ResearchNarrativeGateway gateway,
            ResearchNarrativeValidator validator,
            ResearchNarrativeProperties properties,
            Clock clock
    ) {
        this.snapshotRepository = snapshotRepository;
        this.narrativeRepository = narrativeRepository;
        this.promptBuilder = promptBuilder;
        this.gateway = gateway;
        this.validator = validator;
        this.modelName = properties.modelName();
        this.clock = clock;
    }

    public SaveResearchNarrativeResult generate(UUID snapshotId) {
        StoredGoldResearchSnapshot snapshot = requireSnapshot(snapshotId);

        return narrativeRepository.findByKey(
                snapshotId,
                modelName,
                ResearchNarrativePromptBuilder.PROMPT_VERSION
        ).map(record -> new SaveResearchNarrativeResult(record, false))
                .orElseGet(() -> generateAndSave(snapshot));
    }

    public List<StoredResearchNarrative> findBySnapshotId(UUID snapshotId) {
        requireSnapshot(snapshotId);
        return narrativeRepository.findBySnapshotId(snapshotId);
    }

    /** 只有幂等记录不存在时，才执行会产生费用的模型调用。 */
    private SaveResearchNarrativeResult generateAndSave(
            StoredGoldResearchSnapshot snapshot
    ) {
        ResearchNarrativePrompt prompt = promptBuilder.build(snapshot);
        GeneratedResearchNarrative generated = gateway.generate(prompt);

        validator.validate(generated.content());

        StoredResearchNarrative candidate = new StoredResearchNarrative(
                UUID.randomUUID(),
                snapshot.id(),
                generated.content(),
                generated.modelName(),
                prompt.version(),
                prompt.sha256(),
                generated.rawResponse(),
                OffsetDateTime.now(clock)
        );
        return narrativeRepository.saveIfAbsent(candidate);
    }

    private StoredGoldResearchSnapshot requireSnapshot(UUID snapshotId) {
        Objects.requireNonNull(snapshotId, "快照编号不能为空");
        return snapshotRepository.findById(snapshotId)
                .orElseThrow(() ->
                        new GoldResearchSnapshotNotFoundException(snapshotId)
                );
    }
}
