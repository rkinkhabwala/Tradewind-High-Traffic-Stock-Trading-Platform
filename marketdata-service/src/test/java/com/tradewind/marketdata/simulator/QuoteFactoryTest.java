package com.tradewind.marketdata.simulator;

import com.tradewind.common.money.Price;
import com.tradewind.common.quote.QuoteTick;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteFactoryTest {

    @Test
    void quotesAreOnTheCentTickAndNeverCrossed() {
        SplittableRandom random = new SplittableRandom(7);
        for (int i = 0; i < 50_000; i++) {
            double mid = 0.01 + random.nextDouble() * 2_000;
            double spreadBps = random.nextDouble() * 50;

            QuoteTick q = QuoteFactory.quote("TEST", mid, spreadBps, 100, 200, 10, Instant.EPOCH);

            assertThat(q.bid()).isLessThan(q.ask());
            assertThat(q.last()).isBetween(q.bid(), q.ask());
            assertThat(q.bid().value().movePointRight(2).stripTrailingZeros().scale()).isLessThanOrEqualTo(0);
            assertThat(q.ask().value().movePointRight(2).stripTrailingZeros().scale()).isLessThanOrEqualTo(0);
        }
    }

    @Test
    void spreadIsAtLeastOneCentAndScalesWithPrice() {
        QuoteTick cheap = QuoteFactory.quote("A", 1.00, 4, 100, 100, 1, Instant.EPOCH);
        QuoteTick expensive = QuoteFactory.quote("B", 1_000.00, 4, 100, 100, 1, Instant.EPOCH);

        assertThat(cheap.ask().value().subtract(cheap.bid().value())).isEqualByComparingTo("0.01");
        assertThat(expensive.ask().value().subtract(expensive.bid().value())).isEqualByComparingTo("0.40");
        assertThat(expensive.last()).isEqualTo(Price.of("1000"));
    }
}
