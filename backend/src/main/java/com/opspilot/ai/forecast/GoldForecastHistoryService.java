package com.opspilot.ai.forecast;

import com.opspilot.ai.analysis.history.GoldResearchSnapshotRepository;
import com.opspilot.ai.forecast.api.GoldForecastResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 汇聚最近预测并为其附加基于当时快照的失败归因。
 */
@Service
public class GoldForecastHistoryService {

    private final GoldForecastRepository forecastRepository;
    private final GoldResearchSnapshotRepository snapshotRepository;
    private final GoldForecastMissAnalyzer missAnalyzer;

    public GoldForecastHistoryService(
            GoldForecastRepository forecastRepository,
            GoldResearchSnapshotRepository snapshotRepository,
            GoldForecastMissAnalyzer missAnalyzer
    ) {
        this.forecastRepository = forecastRepository;
        this.snapshotRepository = snapshotRepository;
        this.missAnalyzer = missAnalyzer;
    }

    public List<GoldForecastResponse> retrieveRecent(int limit) {
        return forecastRepository.findRecent(limit).stream()
                .map(forecast -> GoldForecastResponse.from(
                        forecast,
                        snapshotRepository.findById(forecast.snapshotId())
                                .map(stored -> stored.snapshot())
                                .map(snapshot -> missAnalyzer.analyze(forecast, snapshot))
                                .orElse(null)
                ))
                .toList();
    }
}
