package com.tradewind.common.quote;

import com.tradewind.common.event.EventEnvelope;
import com.tradewind.common.event.EventJson;
import com.tradewind.common.event.EventTypes;
import com.tradewind.common.money.Price;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuoteHashTest {

    private static final QuoteTick TICK = new QuoteTick("AAPL", Price.of("187.24"), Price.of("187.26"),
            Price.of("187.25"), 300, 500, 120, Instant.parse("2026-01-05T15:00:00Z"));

    @Test
    void roundTripsThroughRedisHashLayout() {
        Map<String, String> hash = QuoteHash.toHash(TICK, Price.of("185.00"));

        assertThat(QuoteHash.key("AAPL")).isEqualTo("quote:AAPL");
        assertThat(hash).containsEntry("bid", "187.2400").containsEntry("ts", "2026-01-05T15:00:00Z");
        QuoteHash.StoredQuote read = QuoteHash.fromHash("AAPL", hash).orElseThrow();
        assertThat(read.tick().ask()).isEqualTo(TICK.ask());
        assertThat(read.tick().bidSize()).isEqualTo(300);
        assertThat(read.prevClose()).isEqualTo(Price.of("185"));
    }

    @Test
    void incompleteHashIsAbsent() {
        assertThat(QuoteHash.fromHash("AAPL", Map.of())).isEmpty();
        assertThat(QuoteHash.fromHash("AAPL", null)).isEmpty();
    }

    @Test
    void tickRoundTripsAsEventWithoutFloatingPoint() {
        String json = EventJson.write(EventEnvelope.of(EventTypes.QUOTE_TICK, QuoteTick.VERSION, "AAPL", TICK,
                Clock.systemUTC()));

        assertThat(json).contains("\"bid\":\"187.2400\"");
        assertThat(EventJson.read(json, QuoteTick.class).payload()).isEqualTo(TICK);
    }

    @Test
    void rejectsCrossedQuote() {
        assertThatThrownBy(() -> new QuoteTick("AAPL", Price.of("10.01"), Price.of("10.00"), Price.of("10"),
                1, 1, 0, Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
    }
}
