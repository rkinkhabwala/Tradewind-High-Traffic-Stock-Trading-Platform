package com.tradewind.marketdata.candle;

import com.tradewind.common.money.Price;
import com.tradewind.common.quote.QuoteTick;
import com.tradewind.marketdata.simulator.SimulatedTick;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandleAggregatorTest {

    private final CandleAggregator aggregator = new CandleAggregator();

    private static SimulatedTick tick(String time, String last, long volume) {
        Price p = Price.of(last);
        Price bid = Price.of(p.value().subtract(new BigDecimal("0.01")));
        Price ask = Price.of(p.value().add(new BigDecimal("0.01")));
        return new SimulatedTick(new QuoteTick("AAPL", bid, ask, p, 100, 100, volume,
                Instant.parse("2026-01-05T15:" + time + "Z")), p);
    }

    @Test
    void foldsTicksIntoOhlcvPerMinute() {
        aggregator.accept(List.of(tick("00:01", "100.00", 10), tick("00:20", "101.50", 5),
                tick("00:40", "99.25", 7), tick("00:59", "100.75", 3)));

        List<CandleUpdate> updates = aggregator.drain();

        assertThat(updates).singleElement().satisfies(c -> {
            assertThat(c.bucketStart()).isEqualTo(Instant.parse("2026-01-05T15:00:00Z"));
            assertThat(c.open()).isEqualTo(Price.of("100"));
            assertThat(c.high()).isEqualTo(Price.of("101.5"));
            assertThat(c.low()).isEqualTo(Price.of("99.25"));
            assertThat(c.close()).isEqualTo(Price.of("100.75"));
            assertThat(c.volumeDelta()).isEqualTo(25);
        });
    }

    @Test
    void minuteRolloverEmitsTheCompletedBucketAndStartsANewOne() {
        aggregator.accept(List.of(tick("00:30", "100.00", 1), tick("01:05", "102.00", 2)));

        List<CandleUpdate> updates = aggregator.drain();

        assertThat(updates).extracting(CandleUpdate::bucketStart).containsExactly(
                Instant.parse("2026-01-05T15:00:00Z"), Instant.parse("2026-01-05T15:01:00Z"));
        assertThat(updates.get(1).open()).isEqualTo(Price.of("102"));
    }

    @Test
    void repeatedFlushesCarryOnlyNewVolumeAndSkipIdleBuckets() {
        aggregator.accept(List.of(tick("00:10", "100.00", 10)));
        assertThat(aggregator.drain()).singleElement().extracting(CandleUpdate::volumeDelta).isEqualTo(10L);
        assertThat(aggregator.drain()).isEmpty();

        aggregator.accept(List.of(tick("00:20", "100.10", 4)));
        assertThat(aggregator.drain()).singleElement().extracting(CandleUpdate::volumeDelta).isEqualTo(4L);
    }

    @Test
    void failedFlushesAreRequeued() {
        aggregator.accept(List.of(tick("00:10", "100.00", 10)));
        List<CandleUpdate> failed = aggregator.drain();

        aggregator.requeue(failed);

        assertThat(aggregator.drain()).containsExactlyElementsOf(failed);
    }
}
