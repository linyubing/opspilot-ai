package com.opspilot.ai.forecast;

import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.GoldDailyBarRepository;
import com.opspilot.ai.marketdata.GoldSessionCheckService;
import com.opspilot.ai.marketdata.GoldSessionCheckResult;
import com.opspilot.ai.marketdata.GoldSession;
import com.opspilot.ai.marketdata.MarketDataUnavailableException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static com.opspilot.ai.forecast.GoldResolutionCheck.Code.*;

/** 使用后续真实黄金价格确定性解析待验证预测，不调用大模型。 */
@Service
public class GoldForecastResolutionService {

    private static final String GOLD_SYMBOL = "XAUUSD";
    private static final String GOLD_PROVIDER = "twelve_data";

    // 只使用基准日后的第一根记录，不因该记录暂不可用而跳到更晚的目标。
    private static final BigDecimal PERCENT_MULTIPLIER =
            new BigDecimal("100");

    private final GoldForecastRepository forecastRepository;
    private final GoldDailyBarRepository goldRepository;
    private final GoldForecastRule forecastRule;
    private final Clock clock;
    private final GoldSessionCheckService sessionCheck;

    public GoldForecastResolutionService(
            GoldForecastRepository forecastRepository,
            GoldDailyBarRepository goldRepository,
            GoldForecastRule forecastRule,
            Clock clock,
            GoldSessionCheckService sessionCheck
    ) {
        this.forecastRepository = forecastRepository;
        this.goldRepository = goldRepository;
        this.forecastRule = forecastRule;
        this.clock = clock;
        this.sessionCheck = sessionCheck;
    }

    /**
     * 使用后续真实价格批量解析待验证预测。
     *
     * @param limit 本次最多扫描的待验证预测数量
     * @return 扫描、成功解析和继续等待的数量
     */
    public ResolveGoldForecastsResult resolvePending(int limit) {
        validateLimit(limit);

        List<StoredGoldDirectionForecast> pendingForecasts =
                forecastRepository.findPending(limit);

        int resolvedCount = 0;
        int pendingCount = 0;

        for (StoredGoldDirectionForecast forecast : pendingForecasts) {
            boolean resolved;
            try {
                resolved = resolveOne(forecast);
            } finally {
                // 只轮转扫描顺序，不作废预测、不改承诺；异常记录也不永久阻塞后续批次。
                forecastRepository.markScanned(forecast.id());
            }

            if (resolved) {
                resolvedCount++;
            } else {
                pendingCount++;
            }
        }

        return new ResolveGoldForecastsResult(
                pendingForecasts.size(),
                resolvedCount,
                pendingCount
        );
    }

    /** 只读查看已有检查，不发HTTP、不触发结算。 */
    public List<GoldResolutionCheck> checks(UUID id, int limit) {
        validateLimit(limit);
        return forecastRepository.findChecks(id, limit);
    }

    /**
     * 尝试解析一条预测；没有后续有效工作日价格时保持待验证。
     */
    private boolean resolveOne(StoredGoldDirectionForecast forecast) {
        Optional<GoldDailyBar> target = goldRepository.findNext(
                GOLD_SYMBOL,
                GOLD_PROVIDER,
                forecast.baseDate()
        );

        if (target.isEmpty()) {
            return waitFor(forecast, WAITING_BAR, null, null);
        }
        // 同一次校验和落库使用同一时刻，避免跨日或时钟变化造成口径不一致。
        OffsetDateTime asOf = OffsetDateTime.now(clock);
        if (!hasValidTime(forecast, target.get(), asOf)) return waitFor(forecast, INVALID_TIME, null, null);
        if (!target.get().isConfirmedAt(asOf)) return waitFor(forecast, UNCONFIRMED_BAR, null, null);
        if (forecast.timing() != null && !forecast.timing().canResolve(
                forecast.baseDate(), target.get().priceDate(), forecast.createdAt(), asOf)) {
            return waitFor(forecast, asOf.toInstant().isBefore(forecast.timing().end())
                    ? WAITING_END : INVALID_TIMING, null, null);
        }
        // 新正式合同缺少承诺时段时必须等待，不能退回旧协议评分。
        if (forecast.timing() == null && forecast.promptVersion() != null
                && forecast.promptVersion().startsWith("gold-direction-session-prompt-")) {
            return waitFor(forecast, INVALID_TIMING, null, null);
        }
        GoldSessionCheckResult baseProof = null, targetProof = null;
        if (forecast.timing() != null) {
            var bases = goldRepository.findRecent(GOLD_SYMBOL, GOLD_PROVIDER, forecast.baseDate(), 1);
            if (bases.size() != 1) return waitFor(forecast, BASE_UNAVAILABLE, null, null);
            GoldDailyBar base = bases.getFirst();
            if (!forecast.baseDate().equals(base.priceDate()) || !base.isConfirmedAt(asOf)) {
                return waitFor(forecast, BASE_UNAVAILABLE, null, null);
            }
            if (base.close().compareTo(forecast.basePrice()) != 0) {
                return waitFor(forecast, BASE_CHANGED, null, null);
            }
            try {
                // 两端均绑定仓库原价格，不能用最新重下载版本替换冻结基准。
                baseProof = sessionCheck.check(base);
                if (!matches(baseProof, base)) return waitFor(forecast, BASE_SESSION_FAILED, baseProof, null);
                targetProof = sessionCheck.check(target.get());
                if (!matches(targetProof, target.get())) return waitFor(forecast, TARGET_SESSION_FAILED, baseProof, targetProof);
                // HTTP节流可能跨越时刻；写入时必须晚于两份核验，而非沿用请求前时间。
                asOf = OffsetDateTime.now(clock);
                if (baseProof.checkedAt().isAfter(asOf.toInstant())
                        || targetProof.checkedAt().isAfter(asOf.toInstant())
                        || !hasValidTime(forecast, target.get(), asOf)
                        || !target.get().isConfirmedAt(asOf)
                        || !forecast.timing().canResolve(forecast.baseDate(), target.get().priceDate(),
                                forecast.createdAt(), asOf)) return waitFor(forecast, INVALID_TIME, baseProof, targetProof);
            } catch (MarketDataUnavailableException exception) {
                // 来源不可核验时不写实际方向/命中；其他待验证记录仍可继续扫描。
                return waitFor(forecast, SOURCE_UNAVAILABLE, baseProof, targetProof);
            }
        }
        ForecastResolution resolution = createResolution(
                forecast,
                target.get(),
                asOf
        );

        // 记录“检查通过”，不将其伪装成数据库结算成功；写入失败时不得继续评分。
        forecastRepository.recordCheck(new GoldResolutionCheck(UUID.randomUUID(), forecast.id(), asOf,
                forecast.timing() == null ? LEGACY_PRICE_ONLY : CHECK_PASSED, baseProof, targetProof));

        StoredGoldDirectionForecast resolved = forecastRepository.resolve(
                forecast.id(),
                resolution
        );
        /*
         * 数据库只允许 pending 状态更新。
         * 如果并发请求已经先完成解析，仓储会返回数据库中的最终记录。
         */
        return resolved.status() == ForecastStatus.RESOLVED;
    }

    private boolean waitFor(StoredGoldDirectionForecast forecast, GoldResolutionCheck.Code code,
            GoldSessionCheckResult baseProof, GoldSessionCheckResult targetProof) {
        forecastRepository.recordCheck(new GoldResolutionCheck(UUID.randomUUID(), forecast.id(),
                OffsetDateTime.now(clock), code, baseProof, targetProof));
        return false;
    }

    private boolean matches(GoldSessionCheckResult proof, GoldDailyBar day) {
        return proof != null && proof.matched() && proof.checkedAt() != null
                && GoldSession.forDate(day.priceDate()).equals(proof.session())
                && proof.input() != null && day.equals(proof.input().day())
                && ("REQUEST_ONLY_UTC".equals(proof.input().timezoneBasis())
                    || "RESPONSE_UTC".equals(proof.input().timezoneBasis()))
                // 不仅相信MATCHED标签：用绑定原价的小时数据独立重放，拒绝缺行或错价。
                && new com.opspilot.ai.marketdata.GoldSessionCheck().check(
                        day, proof.input().hours(), proof.checkedAt()).matched();
    }

    /** 时间一致性是必要条件，不等同于已经确认供应商日线收盘。 */
    private boolean hasValidTime(StoredGoldDirectionForecast forecast,
            GoldDailyBar target, OffsetDateTime asOf) {
        return target.priceDate() != null
                && target.collectedAt() != null
                && target.priceDate().isAfter(forecast.baseDate())
                && !target.priceDate().isAfter(asOf.toLocalDate())
                && !target.collectedAt().isAfter(asOf);
    }

    /**
     * 根据基准价格和后续真实价格生成确定性的解析结果。
     */
    private ForecastResolution createResolution(
            StoredGoldDirectionForecast forecast,
            GoldDailyBar target,
            OffsetDateTime asOf
    ) {
        BigDecimal actualReturn = calculateReturn(
                forecast.basePrice(),
                target.close()
        );
        // 复用统一规则，不能在解析服务中重新编写方向阈值。
        ForecastDirection actualDirection = forecastRule.classify(actualReturn);

        Boolean hit = forecast.hasSignal() ? forecast.predictedDirection() == actualDirection : null;

        return new ForecastResolution(
                target.priceDate(),
                target.close(),
                actualReturn,
                actualDirection,
                hit,
                asOf
        );
    }

    /**
     * 计算真实涨跌幅百分比，并统一保留 6 位小数。
     */
    private BigDecimal calculateReturn(BigDecimal basePrice, BigDecimal targetPrice) {
        return targetPrice.subtract(basePrice)
                .divide(basePrice, 8, RoundingMode.HALF_UP)
                .multiply(PERCENT_MULTIPLIER)
                .setScale(6, RoundingMode.HALF_UP);
    }

    private void validateLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException(
                    "limit 必须在 1 到 100 之间"
            );
        }
    }
}
