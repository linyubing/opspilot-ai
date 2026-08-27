package com.opspilot.ai.macrodata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 负责把 FRED 通用序列观测转换为实际利率领域数据。 */
@Component
public class FredRealRateProvider implements RealRateProvider {

    private static final Logger log =
            LoggerFactory.getLogger(FredRealRateProvider.class);

    private static final String UNIT = "percent";
    private static final String PROVIDER = "fred";
    private final FredSeriesClient seriesClient;
    private final FredProperties properties;

    public FredRealRateProvider(
            FredSeriesClient seriesClient,
            FredProperties properties
    ) {
        this.seriesClient = seriesClient;
        this.properties = properties;
    }

    @Override
    public RealRateBatch fetchDailyObservations() {
        long startedAt = System.nanoTime();
        FredSeriesBatch sourceBatch = seriesClient.fetch(properties.seriesId());
        RealRateBatch batch = new RealRateBatch(
                sourceBatch.observations().stream()
                        .map(observation -> new IncomingMacroObservation(
                                properties.seriesId(),
                                observation.observationDate(),
                                observation.value(),
                                UNIT,
                                PROVIDER
                        ))
                        .toList(),
                sourceBatch.receivedCount(),
                sourceBatch.missingCount()
        );

        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
        log.info(
                "FRED 实际利率获取完成，序列={}，收到={}，有效={}，缺失={}，耗时={}毫秒",
                properties.seriesId(),
                batch.receivedCount(),
                batch.observations().size(),
                batch.missingCount(),
                elapsedMillis
        );
        return batch;
    }
}
