package com.tradewind.common.event;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Wrapper for every event on Kafka (spec section 10). {@code key} is the Kafka record key
 * (symbol for {@code orders.*}, account id for ledger/executions); {@code version} is the payload schema version.
 */
public record EventEnvelope<T>(UUID eventId, String eventType, Instant occurredAt, int version, String key, T payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(payload, "payload");
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1: " + version);
        }
    }

    public static <T> EventEnvelope<T> of(String eventType, int version, String key, T payload, Clock clock) {
        return new EventEnvelope<>(UUID.randomUUID(), eventType, clock.instant(), version, key, payload);
    }
}
