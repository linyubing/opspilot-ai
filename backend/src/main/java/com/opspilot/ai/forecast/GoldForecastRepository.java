package com.opspilot.ai.forecast;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 定义黄金方向预测的幂等保存、条件解析和评测查询边界。 */
public interface GoldForecastRepository {
    Optional<StoredGoldDirectionForecast> findByKey(
            UUID snapshotId, String modelName, String promptVersion, String ruleVersion);
    SaveGoldForecastResult saveIfAbsent(StoredGoldDirectionForecast candidate);
    List<StoredGoldDirectionForecast> findPending(int limit);
    /** 标记已尝试的待结算记录，避免永久失败记录占住队首。 */
    void markScanned(UUID id);
    List<StoredGoldDirectionForecast> findRecent(int limit);
    List<StoredGoldDirectionForecast> findAllForEvaluation();
    Optional<StoredGoldDirectionForecast> findLatestBySnapshotId(UUID snapshotId);
    StoredGoldDirectionForecast resolve(UUID id, ForecastResolution resolution);
}
