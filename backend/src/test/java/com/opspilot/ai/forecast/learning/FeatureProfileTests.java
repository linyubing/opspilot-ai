package com.opspilot.ai.forecast.learning;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureProfileTests {

    @Test
    void base16Has16Features() {
        assertThat(FeatureProfile.BASE_16.featureNames()).hasSize(16);
    }

    @Test
    void ohlc20Has20Features() {
        assertThat(FeatureProfile.OHLC_20.featureNames()).hasSize(20);
    }

    @Test
    void all36Has36Features() {
        assertThat(FeatureProfile.ALL_36.featureNames()).hasSize(36);
    }

    @Test
    void baseAndOhlcHaveNoOverlap() {
        Set<String> base = FeatureProfile.BASE_16.featureNames();
        Set<String> ohlc = FeatureProfile.OHLC_20.featureNames();

        assertThat(base).doesNotContainAnyElementsOf(ohlc);
    }

    @Test
    void baseAndOhlcUnionEqualsAll() {
        Set<String> base = FeatureProfile.BASE_16.featureNames();
        Set<String> ohlc = FeatureProfile.OHLC_20.featureNames();
        Set<String> all = FeatureProfile.ALL_36.featureNames();

        Set<String> union = Set.copyOf(base);
        union = java.util.stream.Stream.concat(union.stream(), ohlc.stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        assertThat(union).isEqualTo(all);
    }

    @Test
    void featureSetsAreUnmodifiable() {
        Set<String> base = FeatureProfile.BASE_16.featureNames();
        Set<String> ohlc = FeatureProfile.OHLC_20.featureNames();
        Set<String> all = FeatureProfile.ALL_36.featureNames();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> base.add("extra"))
                .isInstanceOf(UnsupportedOperationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ohlc.add("extra"))
                .isInstanceOf(UnsupportedOperationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> all.add("extra"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
