package com.opspilot.ai.forecast;

import com.opspilot.ai.marketdata.MarketPrice;
import com.opspilot.ai.marketdata.MarketPriceRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 使用后续真实黄金价格确定性解析待验证预测，不调用大模型。 */
@Service
public class GoldForecastResolutionService {

    private static final String GOLD_SYMBOL = "XAUUSD";

    /*
     * 单次只读取基准日期之后的前 10 条真实行情。
     * 周末行情会由 NextValidMarketPriceSelector 排除。
     */
    private static final int PRICE_LOOKAHEAD_LIMIT = 10;

    private static final BigDecimal PERCENT_MULTIPLIER =
            new BigDecimal("100");

    private final GoldForecastRepository forecastRepository;
    private final MarketPriceRepository marketPriceRepository;
    private final NextValidMarketPriceSelector priceSelector;
    private final GoldForecastRule forecastRule;
    private final Clock clock;

    public GoldForecastResolutionService(
            GoldForecastRepository forecastRepository,
            MarketPriceRepository marketPriceRepository,
            NextValidMarketPriceSelector priceSelector,
            GoldForecastRule forecastRule,
            Clock clock
    ) {
        this.forecastRepository = forecastRepository;
        this.marketPriceRepository = marketPriceRepository;
        this.priceSelector = priceSelector;
        this.forecastRule = forecastRule;
        this.clock = clock;
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
            boolean resolved = resolveOne(forecast);

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

    /**
     * 尝试解析一条预测；没有后续有效工作日价格时保持待验证。
     */
    private boolean resolveOne(StoredGoldDirectionForecast forecast) {
        List<MarketPrice> candidates = marketPriceRepository.findAfter(
                GOLD_SYMBOL,
                forecast.baseDate(),
                PRICE_LOOKAHEAD_LIMIT
        );

        Optional<MarketPrice> targetPrice = priceSelector.select(candidates);

        if (targetPrice.isEmpty()) {
            return false;
        }
        ForecastResolution resolution = createResolution(
                forecast,
                targetPrice.get()
        );

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
    /**
     * 根据基准价格和后续真实价格生成确定性的解析结果。
     */
    private ForecastResolution createResolution(
            StoredGoldDirectionForecast forecast,
            MarketPrice targetPrice
    ) {
        BigDecimal actualReturn = calculateReturn(
                forecast.basePrice(),
                targetPrice.referencePrice()
        );
        // 复用统一规则，不能在解析服务中重新编写方向阈值。
        ForecastDirection actualDirection = forecastRule.classify(actualReturn);

        boolean hit = forecast.predictedDirection() == actualDirection;

        return new ForecastResolution(
                targetPrice.priceDate(),
                targetPrice.referencePrice(),
                actualReturn,
                actualDirection,
                hit,
                OffsetDateTime.now(clock)
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
