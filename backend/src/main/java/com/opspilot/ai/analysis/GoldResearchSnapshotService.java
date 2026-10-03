package com.opspilot.ai.analysis;

import com.opspilot.ai.macrodata.MacroObservation;
import com.opspilot.ai.macrodata.MacroObservationRepository;
import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.GoldDailyBarRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 负责对齐黄金与实际利率数据并计算确定性研究快照。
 * 本类不调用大模型，也不生成交易信号。
 */
@Service
public class GoldResearchSnapshotService {

    private static final String GOLD_SYMBOL = "XAUUSD";
    private static final String GOLD_PROVIDER = "twelve_data";
    private static final String REAL_RATE_SERIES_ID = "DFII10";
    private static final String DOLLAR_INDEX_SERIES_ID = "DTWEXBGS";
    public static final String RESEARCH_VERSION = "gold-multifactor-confirmed-v3";
    private static final int QUERY_LIMIT = 120;
    private static final int REQUIRED_OBSERVATION_COUNT = 21;
    private static final ZoneId MARKET_ZONE =
            ZoneId.of("America/New_York");

    private static final BigDecimal ONE_HUNDRED =
            new BigDecimal("100");

    private static final String DISCLAIMER =
            "实际利率状态仅代表单一研究因素，"
                    + "不构成黄金方向预测或投资建议。";

    private final GoldDailyBarRepository goldRepository;
    private final MacroObservationRepository macroObservationRepository;
    private final RealRateFactorEvaluator evaluator;
    private final DollarIndexFactorEvaluator dollarIndexEvaluator;
    private final Clock clock;
    private final GoldVolatilityCalculator volatilityCalculator =
            new GoldVolatilityCalculator();

    public GoldResearchSnapshotService(
            GoldDailyBarRepository goldRepository,
            MacroObservationRepository macroObservationRepository,
            RealRateFactorEvaluator evaluator,
            DollarIndexFactorEvaluator dollarIndexEvaluator
    ) {
        this(
                goldRepository,
                macroObservationRepository,
                evaluator,
                dollarIndexEvaluator,
                Clock.system(MARKET_ZONE)
        );
    }

    @Autowired
    public GoldResearchSnapshotService(
            GoldDailyBarRepository goldRepository,
            MacroObservationRepository macroObservationRepository,
            RealRateFactorEvaluator evaluator,
            DollarIndexFactorEvaluator dollarIndexEvaluator,
            Clock clock
    ) {
        this.goldRepository = goldRepository;
        this.macroObservationRepository = macroObservationRepository;
        this.evaluator = evaluator;
        this.dollarIndexEvaluator = dollarIndexEvaluator;
        this.clock = clock;
    }

    public GoldResearchSnapshot createSnapshot() {
        OffsetDateTime checkedAt = checkTime();
        List<GoldDailyBar> goldPrices = goldRepository.findRecent(
                GOLD_SYMBOL,
                GOLD_PROVIDER,
                QUERY_LIMIT
        );

        List<MacroObservation> realRates =
                macroObservationRepository.findRecent(
                        REAL_RATE_SERIES_ID,
                        QUERY_LIMIT
                );

        List<MacroObservation> dollarIndexes =
                macroObservationRepository.findRecent(
                        DOLLAR_INDEX_SERIES_ID,
                        QUERY_LIMIT
                );

        return calculate(
                removeOpenMarketDay(goldPrices, checkedAt),
                realRates,
                dollarIndexes,
                checkedAt
        );
    }

    /**
     * 旧历史快照：只限制观测日期，不保证值是当时公布版本；模型实验不得使用此入口。
     */
    public GoldResearchSnapshot createSnapshot(LocalDate asOf) {
        Objects.requireNonNull(asOf, "回测日期不能为空");
        OffsetDateTime checkedAt = checkTime();

        return calculate(
                goldRepository.findRecent(
                        GOLD_SYMBOL,
                        GOLD_PROVIDER,
                        asOf,
                        QUERY_LIMIT
                ),
                macroObservationRepository.findRecent(
                        REAL_RATE_SERIES_ID,
                        asOf,
                        QUERY_LIMIT
                ),
                macroObservationRepository.findRecent(
                        DOLLAR_INDEX_SERIES_ID,
                        asOf,
                        QUERY_LIMIT
                ),
                checkedAt
        );
    }

    /** 冻结黄金窗口与核验时刻；宏观只限观测日，不保证历史公布版本。 */
    public GoldResearchSnapshot createSnapshot(LocalDate asOf, List<GoldDailyBar> goldPrices,
            OffsetDateTime checkedAt) {
        Objects.requireNonNull(asOf, "回测日期不能为空");
        return createSnapshot(asOf, goldPrices,
                macroObservationRepository.findRecent(REAL_RATE_SERIES_ID, asOf, QUERY_LIMIT),
                macroObservationRepository.findRecent(DOLLAR_INDEX_SERIES_ID, asOf, QUERY_LIMIT),
                checkedAt);
    }

    /** 使用调用方冻结的历史可得宏观观测，复用快照公式，不读取最新修订值。 */
    public GoldResearchSnapshot createSnapshot(LocalDate asOf,
            List<MacroObservation> realRates, List<MacroObservation> dollarIndexes) {
        Objects.requireNonNull(asOf, "回测日期不能为空");
        OffsetDateTime checkedAt = checkTime();
        return calculate(goldRepository.findRecent(GOLD_SYMBOL, GOLD_PROVIDER, asOf, QUERY_LIMIT),
                realRates, dollarIndexes, checkedAt);
    }

    /** 共用调用方冻结的黄金与宏观窗口，核验时刻不是历史可得性证明。 */
    public GoldResearchSnapshot createSnapshot(LocalDate asOf, List<GoldDailyBar> goldPrices,
            List<MacroObservation> realRates, List<MacroObservation> dollarIndexes,
            OffsetDateTime checkedAt) {
        Objects.requireNonNull(asOf, "回测日期不能为空");
        Objects.requireNonNull(checkedAt, "核验时刻不能为空");
        if (goldPrices != null && goldPrices.stream().anyMatch(bar -> bar != null
                && bar.priceDate() != null && bar.priceDate().isAfter(asOf))) {
            throw new InvalidResearchDataException("冻结黄金窗口不能包含回测日期之后的行情");
        }
        return calculate(goldPrices, realRates, dollarIndexes, checkedAt);
    }

    /** 自行采样统一到数据库微秒精度；调用方冻结的时刻保持原样。 */
    private OffsetDateTime checkTime() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private GoldResearchSnapshot calculate(
            List<GoldDailyBar> goldPrices,
            List<MacroObservation> realRates,
            List<MacroObservation> dollarIndexes,
            OffsetDateTime checkedAt
    ) {

        validateSourceData(goldPrices, realRates, dollarIndexes);

        Map<LocalDate, GoldDailyBar> goldByDate =
                indexGoldPrices(goldPrices);

        Map<LocalDate, MacroObservation> realRateByDate =
                indexRealRates(realRates);

        Map<LocalDate, MacroObservation> dollarIndexByDate =
                indexDollarIndexes(dollarIndexes);

        List<GoldDailyBar> sortedGold = goldByDate.values().stream()
                .sorted(Comparator.comparing(GoldDailyBar::priceDate).reversed())
                .toList();
        List<MacroObservation> sortedRates = realRateByDate.values().stream()
                .sorted(Comparator.comparing(MacroObservation::observationDate).reversed())
                .toList();
        List<MacroObservation> sortedDollars = dollarIndexByDate.values().stream()
                .sorted(Comparator.comparing(MacroObservation::observationDate).reversed())
                .toList();

        validateCount("黄金价格", sortedGold.size());
        validateCount("实际利率", sortedRates.size());
        validateCount("广义美元指数", sortedDollars.size());

        /*
         * 各市场按自己的发布节奏取最新值，不要求观测日期完全相同。
         * 原始日期仍保留在快照中，不能把旧宏观数据伪装成黄金基准日数据。
         */
        GoldDailyBar currentGold = sortedGold.get(0);
        GoldDailyBar gold1 = sortedGold.get(1);
        GoldDailyBar gold5 = sortedGold.get(5);
        GoldDailyBar gold20 = sortedGold.get(20);

        MacroObservation currentRealRate = sortedRates.get(0);
        MacroObservation realRate1 = sortedRates.get(1);
        MacroObservation realRate5 = sortedRates.get(5);
        MacroObservation realRate20 = sortedRates.get(20);

        MacroObservation currentDollarIndex = sortedDollars.get(0);
        MacroObservation dollarIndex1 = sortedDollars.get(1);
        MacroObservation dollarIndex5 = sortedDollars.get(5);
        MacroObservation dollarIndex20 = sortedDollars.get(20);

        LocalDate latestGoldDate = currentGold.priceDate();
        LocalDate latestRealRateDate = currentRealRate.observationDate();
        LocalDate latestDollarIndexDate = currentDollarIndex.observationDate();

        validateGoldPrice(currentGold);
        validateGoldPrice(gold1);
        validateGoldPrice(gold5);
        validateGoldPrice(gold20);

        // 检查实际参与收益和波动率计算的全部21根，不删除窗口中的未确认行。
        for (GoldDailyBar bar : sortedGold.subList(0, REQUIRED_OBSERVATION_COUNT)) {
            if (!bar.isConfirmedAt(checkedAt)) {
                throw new InsufficientResearchDataException("黄金日线未确认，日期=" + bar.priceDate());
            }
        }

        validateRealRate(currentRealRate);
        validateRealRate(realRate1);
        validateRealRate(realRate5);
        validateRealRate(realRate20);

        validateDollarIndex(currentDollarIndex);
        validateDollarIndex(dollarIndex1);
        validateDollarIndex(dollarIndex5);
        validateDollarIndex(dollarIndex20);

        GoldReturnMetrics goldMetrics = new GoldReturnMetrics(
                currentGold.close(),
                calculateReturn(
                        currentGold.close(),
                        gold1.close()
                ),
                calculateReturn(
                        currentGold.close(),
                        gold5.close()
                ),
                calculateReturn(
                        currentGold.close(),
                        gold20.close()
                ),
                volatilityCalculator.calculateBars(sortedGold),
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

        DollarIndexChangeMetrics dollarIndexMetrics =
                new DollarIndexChangeMetrics(
                        currentDollarIndex.value(),
                        calculateReturn(
                                currentDollarIndex.value(),
                                dollarIndex1.value()
                        ),
                        calculateReturn(
                                currentDollarIndex.value(),
                                dollarIndex5.value()
                        ),
                        calculateReturn(
                                currentDollarIndex.value(),
                                dollarIndex20.value()
                        ),
                        currentDollarIndex.collectedAt()
                );

        ResearchFactorAssessment realRateAssessment =
                evaluator.evaluate(
                        realRateMetrics.basisPointChange5(),
                        realRateMetrics.basisPointChange20()
                );

        ResearchFactorAssessment dollarIndexAssessment =
                dollarIndexEvaluator.evaluate(
                        dollarIndexMetrics.return5(),
                        dollarIndexMetrics.return20()
                );

        return new GoldResearchSnapshot(
                latestGoldDate,
                latestGoldDate,
                latestRealRateDate,
                latestDollarIndexDate,
                goldMetrics,
                realRateMetrics,
                dollarIndexMetrics,
                realRateAssessment,
                dollarIndexAssessment,
                RESEARCH_VERSION,
                DISCLAIMER,
                new GoldSnapshotInput(sortedGold.subList(0, REQUIRED_OBSERVATION_COUNT), checkedAt)
        );
    }

    private List<GoldDailyBar> removeOpenMarketDay(
            List<GoldDailyBar> prices, OffsetDateTime checkedAt
    ) {
        LocalDate marketToday = checkedAt.atZoneSameInstant(MARKET_ZONE).toLocalDate();
        return prices.stream()
                // 当天日线在交易日结束前只是盘中蜡烛，不能当正式收盘价。
                .filter(price -> price.priceDate() == null
                        || price.priceDate().isBefore(marketToday))
                .toList();
    }

    private void validateCount(String dataName, int count) {
        if (count < REQUIRED_OBSERVATION_COUNT) {
            throw new InsufficientResearchDataException(
                    dataName + "观测数量不足，实际=" + count
                            + "，最低要求=" + REQUIRED_OBSERVATION_COUNT
            );
        }
    }

    private void validateSourceData(
            List<GoldDailyBar> goldPrices,
            List<MacroObservation> realRates,
            List<MacroObservation> dollarIndexes
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

        if (dollarIndexes == null || dollarIndexes.isEmpty()) {
            throw new InsufficientResearchDataException(
                    "没有可用于研究的广义美元指数"
            );
        }

        /*
         * 在排序和建立日期索引前检查日期，
         * 避免底层比较器抛出含义不明确的空指针异常。
         */
        for (GoldDailyBar price : goldPrices) {
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


        for (MacroObservation observation : dollarIndexes) {
            if (observation == null
                    || observation.observationDate() == null) {
                throw new InvalidResearchDataException(
                        "广义美元指数观测日期不能为空"
                );
            }
        }
    }

    private Map<LocalDate, GoldDailyBar> indexGoldPrices(
            List<GoldDailyBar> goldPrices
    ) {
        Map<LocalDate, GoldDailyBar> result = new HashMap<>();

        for (GoldDailyBar price : goldPrices) {
            GoldDailyBar previous = result.putIfAbsent(
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

    private Map<LocalDate, MacroObservation> indexDollarIndexes(
            List<MacroObservation> dollarIndexes
    ) {
        Map<LocalDate, MacroObservation> result = new HashMap<>();

        for (MacroObservation observation : dollarIndexes) {
            MacroObservation previous = result.putIfAbsent(
                    observation.observationDate(),
                    observation
            );
            if (previous != null) {
                throw new InvalidResearchDataException(
                        "广义美元指数存在重复观测日期："
                                + observation.observationDate()
                );
            }
        }
        return result;
    }

    private void validateGoldPrice(GoldDailyBar price) {
        if (price.close() == null
                || price.close().compareTo(
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

    private void validateDollarIndex(MacroObservation observation) {
        if (observation.value() == null
                || observation.value().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidResearchDataException(
                    "广义美元指数必须大于 0，日期："
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
