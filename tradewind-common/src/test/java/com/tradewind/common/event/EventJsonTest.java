package com.tradewind.common.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradewind.common.money.Money;
import com.tradewind.common.money.Price;
import com.tradewind.common.money.Quantity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventJsonTest {

    public record SamplePayload(UUID orderId, Money amount, Quantity qty, Price price) {
    }

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-05T14:30:00Z"), ZoneOffset.UTC);
    private static final SamplePayload PAYLOAD = new SamplePayload(
            UUID.fromString("7d3f0c1e-1111-4a4a-9b9b-222233334444"),
            Money.ofCents(12_345), Quantity.of("1.5"), Price.of("187.25"));

    @Test
    void moneyTypesSerializeWithoutFloatingPoint() {
        String json = EventJson.write(EventEnvelope.of("Sample", 1, "AAPL", PAYLOAD, CLOCK));

        assertThat(json)
                .contains("\"amount\":12345")
                .contains("\"qty\":\"1.500000\"")
                .contains("\"price\":\"187.2500\"")
                .contains("\"occurredAt\":\"2026-01-05T14:30:00Z\"")
                .contains("\"key\":\"AAPL\"");
    }

    @Test
    void roundTripsTypedEnvelope() {
        EventEnvelope<SamplePayload> original = EventEnvelope.of("Sample", 1, "AAPL", PAYLOAD, CLOCK);

        EventEnvelope<SamplePayload> read = EventJson.read(EventJson.write(original), SamplePayload.class);

        assertThat(read).isEqualTo(original);
    }

    @Test
    void rawReadAllowsDispatchOnEventTypeBeforeBindingPayload() {
        String json = EventJson.write(EventEnvelope.of("Sample", 2, "AAPL", PAYLOAD, CLOCK));

        EventEnvelope<JsonNode> raw = EventJson.readRaw(json);

        assertThat(raw.eventType()).isEqualTo("Sample");
        assertThat(raw.version()).isEqualTo(2);
        assertThat(EventJson.convert(raw.payload(), SamplePayload.class)).isEqualTo(PAYLOAD);
    }

    @Test
    void ignoresUnknownFieldsForForwardCompatibility() {
        String json = EventJson.write(EventEnvelope.of("Sample", 1, "AAPL", PAYLOAD, CLOCK))
                .replace("\"amount\":12345", "\"amount\":12345,\"addedInV2\":\"x\"");

        assertThat(EventJson.read(json, SamplePayload.class).payload()).isEqualTo(PAYLOAD);
    }

    @Test
    void envelopeRejectsInvalidMetadata() {
        assertThatThrownBy(() -> EventEnvelope.of("Sample", 0, "AAPL", PAYLOAD, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventEnvelope.of("Sample", 1, " ", PAYLOAD, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventEnvelope.of(" ", 1, "AAPL", PAYLOAD, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
