package com.skillmasterai.modules.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RelevanceWeightsTest {

    @Test
    void refusesWeightsThatAddUpPastWhatTheScoreCanHold() {
        // The score is the three weights added and nothing else — the query uses each as a CASE's
        // result, never multiplied by a hit count — so this bound is exact. Past it PostgreSQL
        // raises "integer out of range" on every search, at query time, which is the sort of
        // misconfiguration worth hearing about at startup instead.
        assertThatThrownBy(() -> new RelevanceWeights(Integer.MAX_VALUE, Integer.MAX_VALUE, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("add up");

        assertThat(new RelevanceWeights(Integer.MAX_VALUE - 2, 1, 1).name())
                .as("and a sum that just fits is still accepted")
                .isEqualTo(Integer.MAX_VALUE - 2);
    }

    @Test
    void refusesWeightsThatIncreaseDownTheFields() {
        assertThatThrownBy(() -> new RelevanceWeights(10, 20, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not increase");
    }
}
