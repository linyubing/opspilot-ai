package com.opspilot.ai.analysis;

import com.opspilot.ai.marketdata.MarketPrice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/** 依据最近 20 个黄金日收益率计算年化实际波动率。 */
public class GoldVolatilityCalculator {

    private static final int PRICE_COUNT = 21;
    private static final double TRADING_DAYS = 252.0;

    public BigDecimal calculate(List<MarketPrice> prices) {
        if (prices == null || prices.size() < PRICE_COUNT) {
            throw new InsufficientResearchDataException(
                    "计算20日实际波动率至少需要21条黄金价格"
            );
        }

        List<MarketPrice> recent = prices.stream()
                .sorted(Comparator.comparing(MarketPrice::priceDate))
                .skip(prices.size() - PRICE_COUNT)
                .toList();

        double[] returns = new double[PRICE_COUNT - 1];
        double sum = 0;
        for (int index = 1; index < recent.size(); index++) {
            double previous = value(recent.get(index - 1));
            double current = value(recent.get(index));
            double dailyReturn = Math.log(current / previous);
            returns[index - 1] = dailyReturn;
            sum += dailyReturn;
        }

        double average = sum / returns.length;
        double squaredSum = 0;
        for (double dailyReturn : returns) {
            double difference = dailyReturn - average;
            squaredSum += difference * difference;
        }

        double annualized = Math.sqrt(squaredSum / returns.length)
                * Math.sqrt(TRADING_DAYS)
                * 100;
        return BigDecimal.valueOf(annualized)
                .setScale(4, RoundingMode.HALF_UP);
    }

    private double value(MarketPrice price) {
        if (price == null
                || price.referencePrice() == null
                || price.referencePrice().signum() <= 0) {
            throw new InvalidResearchDataException(
                    "计算实际波动率的黄金价格必须大于0"
            );
        }
        return price.referencePrice().doubleValue();
    }
}
