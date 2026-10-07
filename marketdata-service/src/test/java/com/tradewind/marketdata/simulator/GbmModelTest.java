package com.tradewind.marketdata.simulator;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GbmModelTest {

    @Test
    void zeroVolatilityGrowsAtTheDrift() {
        double price = GbmModel.step(100, 0.05, 0, 1.0, 1.7, 0);

        assertThat(price).isCloseTo(100 * Math.exp(0.05), within(1e-9));
    }

    @Test
    void logReturnsHaveTheConfiguredVolatility() {
        SplittableRandom random = new SplittableRandom(42);
        double sigma = 0.30;
        double dt = 1.0 / 252;
        int steps = 100_000;
        double sum = 0;
        double sumSq = 0;
        double price = 100;
        for (int i = 0; i < steps; i++) {
            double next = GbmModel.step(price, 0, sigma, dt, random.nextGaussian(), 0);
            double r = Math.log(next / price);
            sum += r;
            sumSq += r * r;
            price = 100; // reset so the floor never interferes
        }
        double mean = sum / steps;
        double annualisedVol = Math.sqrt((sumSq / steps - mean * mean) / dt);

        assertThat(annualisedVol).isCloseTo(sigma, within(0.005));
        assertThat(mean).isCloseTo(-0.5 * sigma * sigma * dt, within(0.0002));
    }

    @Test
    void priceNeverFallsBelowTheFloor() {
        assertThat(GbmModel.step(0.06, 0, 5, 1, -10, -5)).isEqualTo(GbmModel.MIN_PRICE);
    }
}
