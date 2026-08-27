package com.opspilot.ai.macrodata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 负责把 FRED 通用序列观测转换为广义美元指数领域数据。 */
@Component
public class FredDollarIndexProvider implements DollarIndexProvider {

    private static final Logger log =
            LoggerFactory.getLogger(FredDollarIndexProvider.class);
    private static final String UNIT = "index_2006_100";
    private static final String PROVIDER = "fred";

    private final FredSeriesClient seriesClient;
    private final FredProperties properties;

    public FredDollarIndexProvider(
            FredSeriesClient seriesClient,
            FredProperties properties
    ) {
        this.seriesClient = seriesClient;
        this.properties = properties;
    }

    @Override
    public DollarIndexBatch fetchDailyObservations() {
        long startedAt = System.nanoTime();
        FredSeriesBatch sourceBatch = seriesClient.fetch(
                properties.dollarIndexSeriesId()
        );
        DollarIndexBatch batch = new DollarIndexBatch(
                sourceBatch.observations().stream()
                        .map(observation -> new IncomingMacroObservation(
                                properties.dollarIndexSeriesId(),
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
                "FRED 广义美元指数获取完成，序列={}，收到={}，有效={}，缺失={}，耗时={}毫秒",
                properties.dollarIndexSeriesId(),
                batch.receivedCount(),
                batch.observations().size(),
                batch.missingCount(),
                elapsedMillis
        );
        return batch;
    }
}
