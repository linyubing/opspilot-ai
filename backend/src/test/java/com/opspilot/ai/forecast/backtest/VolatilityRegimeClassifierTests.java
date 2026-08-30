package com.opspilot.ai.forecast.backtest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class VolatilityRegimeClassifierTests {

    @Test
    void classifiesWithPreviousValuesOnly() {
        List<BigDecimal> history = IntStream.rangeClosed(1, 30)
                .mapToObj(BigDecimal::valueOf)
                .toList();
        VolatilityRegimeClassifier classifier =
                new VolatilityRegimeClassifier();

        assertThat(classifier.classify(new BigDecimal("5"), history))
                .isEqualTo(VolatilityRegime.LOW);
        assertThat(classifier.classify(new BigDecimal("15"), history))
                .isEqualTo(VolatilityRegime.MEDIUM);
        assertThat(classifier.classify(new BigDecimal("25"), history))
                .isEqualTo(VolatilityRegime.HIGH);
    }
}
