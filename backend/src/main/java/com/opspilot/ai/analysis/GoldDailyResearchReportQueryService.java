package com.opspilot.ai.analysis;

import com.opspilot.ai.analysis.history.GoldResearchSnapshotRepository;
import com.opspilot.ai.analysis.history.InvalidResearchHistoryRequestException;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import com.opspilot.ai.analysis.narrative.ResearchNarrativeRepository;
import com.opspilot.ai.analysis.narrative.StoredResearchNarrative;
import com.opspilot.ai.forecast.GoldForecastRepository;
import com.opspilot.ai.forecast.StoredGoldDirectionForecast;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

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

    public List<StoredGoldDailyResearchReport> findRecentCompleteReports(
            int limit
    ) {
        validateLimit(limit);

        // 任一组成部分缺失时 Optional 为空，最终只保留完整日报。
        return snapshotRepository.findRecent(limit).stream()
                .map(this::assembleIfComplete)
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<StoredGoldDailyResearchReport> assembleIfComplete(
            StoredGoldResearchSnapshot snapshot
    ) {
        return narrativeRepository.findLatestBySnapshotId(snapshot.id())
                .flatMap(narrative -> forecastRepository
                        .findLatestBySnapshotId(snapshot.id())
                        .map(forecast -> new StoredGoldDailyResearchReport(
                                snapshot,
                                narrative,
                                forecast
                        )));
    }

    private void validateLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new InvalidResearchHistoryRequestException(
                    "limit 必须在 1 到 100 之间"
            );
        }
    }

    private GoldDailyResearchReportNotFoundException notFound(
            String missingPart
    ) {
        return new GoldDailyResearchReportNotFoundException(missingPart);
    }
}
