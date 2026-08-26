package com.opspilot.ai.analysis;

import com.opspilot.ai.macrodata.MacroObservation;
import com.opspilot.ai.macrodata.MacroObservationRepository;
import com.opspilot.ai.marketdata.MarketPrice;
import com.opspilot.ai.marketdata.MarketPriceRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class GoldResearchSnapshotService {

    private static final String GOLD_SYMBOL = "XAUUSD";
    private static final String REAL_RATE_SERIES_ID = "DFII10";
    private static final int QUERY_LIMIT = 120;
    private static final int REQUIRED_COMMON_DATE_COUNT = 21;

    private static final BigDecimal ONE_HUNDRED =
            new BigDecimal("100");

    private static final String DISCLAIMER =
            "实际利率状态仅代表单一研究因素，"
                    + "不构成黄金方向预测或投资建议。";

    private final MarketPriceRepository marketPriceRepository;
    private final MacroObservationRepository macroObservationRepository;
    private final RealRateFactorEvaluator evaluator;

    public GoldResearchSnapshotService(
            MarketPriceRepository marketPriceRepository,
            MacroObservationRepository macroObservationRepository,
            RealRateFactorEvaluator evaluator
    ) {
        this.marketPriceRepository = marketPriceRepository;
        this.macroObservationRepository = macroObservationRepository;
        this.evaluator = evaluator;
    }

    public GoldResearchSnapshot createSnapshot() {
        List<MarketPrice> goldPrices =
                marketPriceRepository.findRecent(
                        GOLD_SYMBOL,
                        QUERY_LIMIT
                );

        List<MacroObservation> realRates =
                macroObservationRepository.findRecent(
                        REAL_RATE_SERIES_ID,
                        QUERY_LIMIT
                );

        validateSourceData(goldPrices, realRates);

        LocalDate latestGoldDate = goldPrices.stream()
                .map(MarketPrice::priceDate)
                .max(LocalDate::compareTo)
                .orElseThrow();

        LocalDate latestRealRateDate = realRates.stream()
                .map(MacroObservation::observationDate)
                .max(LocalDate::compareTo)
                .orElseThrow();

        Map<LocalDate, MarketPrice> goldByDate =
                indexGoldPrices(goldPrices);

        Map<LocalDate, MacroObservation> realRateByDate =
                indexRealRates(realRates);

        /*
         * 只保留两个数据源都有记录的日期。
         * 不使用插值，也不拿前一个工作日的数据冒充当天数据。
         */
        List<LocalDate> commonDates = goldByDate.keySet()
                .stream()
                .filter(realRateByDate::containsKey)
                .sorted(Comparator.reverseOrder())
                .toList();

        if (commonDates.size() < REQUIRED_COMMON_DATE_COUNT) {
            throw new InsufficientResearchDataException(
                    "共同观测日期不足，实际="
                            + commonDates.size()
                            + "，最低要求="
                            + REQUIRED_COMMON_DATE_COUNT
            );
        }

        MarketPrice currentGold =
                goldByDate.get(commonDates.get(0));
        MarketPrice gold1 =
                goldByDate.get(commonDates.get(1));
        MarketPrice gold5 =
                goldByDate.get(commonDates.get(5));
        MarketPrice gold20 =
                goldByDate.get(commonDates.get(20));

        MacroObservation currentRealRate =
                realRateByDate.get(commonDates.get(0));
        MacroObservation realRate1 =
                realRateByDate.get(commonDates.get(1));
        MacroObservation realRate5 =
                realRateByDate.get(commonDates.get(5));
        MacroObservation realRate20 =
                realRateByDate.get(commonDates.get(20));

        validateGoldPrice(currentGold);
        validateGoldPrice(gold1);
        validateGoldPrice(gold5);
        validateGoldPrice(gold20);

        validateRealRate(currentRealRate);
        validateRealRate(realRate1);
        validateRealRate(realRate5);
        validateRealRate(realRate20);

        GoldReturnMetrics goldMetrics = new GoldReturnMetrics(
                currentGold.referencePrice(),
                calculateReturn(
                        currentGold.referencePrice(),
                        gold1.referencePrice()
                ),
                calculateReturn(
                        currentGold.referencePrice(),
                        gold5.referencePrice()
                ),
                calculateReturn(
                        currentGold.referencePrice(),
                        gold20.referencePrice()
                ),
                currentGold.collectedAt()
        );

        BigDecimal rateChange1 = percentagePointChange(
                currentRealRate.value(),
                realRate1.value()
        );
        BigDecimal rateChange5 = percentagePointChange(
                currentRealRate.value(),
                realRate5.value()
        );
        BigDecimal rateChange20 = percentagePointChange(
                currentRealRate.value(),
                realRate20.value()
        );

        RealRateChangeMetrics realRateMetrics =
                new RealRateChangeMetrics(
                        currentRealRate.value(),
                        rateChange1,
                        rateChange5,
                        rateChange20,
                        toBasisPoints(rateChange1),
                        toBasisPoints(rateChange5),
                        toBasisPoints(rateChange20),
                        currentRealRate.collectedAt()
                );

        ResearchFactorAssessment assessment =
                evaluator.evaluate(
                        realRateMetrics.basisPointChange5(),
                        realRateMetrics.basisPointChange20()
                );

        return new GoldResearchSnapshot(
                commonDates.get(0),
                latestGoldDate,
                latestRealRateDate,
                goldMetrics,
                realRateMetrics,
                assessment,
                DISCLAIMER
        );
    }

    private void validateSourceData(
            List<MarketPrice> goldPrices,
            List<MacroObservation> realRates
    ) {
        if (goldPrices == null || goldPrices.isEmpty()) {
            throw new InsufficientResearchDataException(
                    "没有可用于研究的黄金价格"
            );
        }

        if (realRates == null || realRates.isEmpty()) {
            throw new InsufficientResearchDataException(
                    "没有可用于研究的实际利率"
            );
        }

        /*
         * 在排序和建立日期索引前检查日期，
         * 避免底层比较器抛出含义不明确的空指针异常。
         */
        for (MarketPrice price : goldPrices) {
            if (price == null || price.priceDate() == null) {
                throw new InvalidResearchDataException(
                        "黄金观测日期不能为空"
                );
            }
        }

        for (MacroObservation observation : realRates) {
            if (observation == null
                    || observation.observationDate() == null) {
                throw new InvalidResearchDataException(
                        "实际利率观测日期不能为空"
                );
            }
        }
    }

    private Map<LocalDate, MarketPrice> indexGoldPrices(
            List<MarketPrice> goldPrices
    ) {
        Map<LocalDate, MarketPrice> result = new HashMap<>();

        for (MarketPrice price : goldPrices) {
            MarketPrice previous = result.putIfAbsent(
                    price.priceDate(),
                    price
            );

            if (previous != null) {
                throw new InvalidResearchDataException(
                        "黄金价格存在重复观测日期："
                                + price.priceDate()
                );
            }
        }

        return result;
    }

    private Map<LocalDate, MacroObservation> indexRealRates(
            List<MacroObservation> realRates
    ) {
        Map<LocalDate, MacroObservation> result = new HashMap<>();

        for (MacroObservation observation : realRates) {
            MacroObservation previous = result.putIfAbsent(
                    observation.observationDate(),
                    observation
            );

            if (previous != null) {
                throw new InvalidResearchDataException(
                        "实际利率存在重复观测日期："
                                + observation.observationDate()
                );
            }
        }

        return result;
    }

    private void validateGoldPrice(MarketPrice price) {
        if (price.referencePrice() == null
                || price.referencePrice().compareTo(
                BigDecimal.ZERO
        ) <= 0) {
            throw new InvalidResearchDataException(
                    "黄金价格必须大于 0，日期："
                            + price.priceDate()
            );
        }
    }

    private void validateRealRate(MacroObservation observation) {
        if (observation.value() == null) {
            throw new InvalidResearchDataException(
                    "实际利率不能为空，日期："
                            + observation.observationDate()
            );
        }
    }

    /**
     * 计算黄金涨跌幅，API 统一保留 4 位小数。
     */
    private BigDecimal calculateReturn(
            BigDecimal current,
            BigDecimal base
    ) {
        return current
                .divide(base, MathContext.DECIMAL128)
                .subtract(BigDecimal.ONE)
                .multiply(ONE_HUNDRED)
                .setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 实际利率使用百分点差值，不计算相对涨跌百分比。
     */
    private BigDecimal percentagePointChange(
            BigDecimal current,
            BigDecimal base
    ) {
        return current
                .subtract(base)
                .setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal toBasisPoints(
            BigDecimal percentagePointChange
    ) {
        return percentagePointChange
                .multiply(ONE_HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
    }
}