package com.opspilot.ai.analysis;

import com.opspilot.ai.analysis.history.GoldResearchSnapshotRepository;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import com.opspilot.ai.analysis.narrative.ResearchNarrativeRepository;
import com.opspilot.ai.analysis.narrative.StoredResearchNarrative;
import com.opspilot.ai.forecast.GoldForecastRepository;
import com.opspilot.ai.forecast.StoredGoldDirectionForecast;
import org.springframework.stereotype.Service;

/** 从数据库读取并组装最新的完整黄金研究日报，不触发外部数据或模型调用。 */
@Service
public class GoldDailyResearchReportQueryService {

    private final GoldResearchSnapshotRepository snapshotRepository;
    private final ResearchNarrativeRepository narrativeRepository;
    private final GoldForecastRepository forecastRepository;

    public GoldDailyResearchReportQueryService(
            GoldResearchSnapshotRepository snapshotRepository,
            ResearchNarrativeRepository narrativeRepository,
            GoldForecastRepository forecastRepository
    ) {
        this.snapshotRepository = snapshotRepository;
        this.narrativeRepository = narrativeRepository;
        this.forecastRepository = forecastRepository;
    }

    public StoredGoldDailyResearchReport findLatestCompleteReport() {
        StoredGoldResearchSnapshot snapshot = snapshotRepository.findRecent(1)
                .stream()
                .findFirst()
                .orElseThrow(() -> notFound("研究快照"));

        StoredResearchNarrative narrative = narrativeRepository
                .findLatestBySnapshotId(snapshot.id())
                .orElseThrow(() -> notFound("研究解读"));
        StoredGoldDirectionForecast forecast = forecastRepository
                .findLatestBySnapshotId(snapshot.id())
                .orElseThrow(() -> notFound("方向预测"));

        return new StoredGoldDailyResearchReport(
                snapshot,
                narrative,
                forecast
        );
    }

    private GoldDailyResearchReportNotFoundException notFound(
            String missingPart
    ) {
        return new GoldDailyResearchReportNotFoundException(missingPart);
    }
}
