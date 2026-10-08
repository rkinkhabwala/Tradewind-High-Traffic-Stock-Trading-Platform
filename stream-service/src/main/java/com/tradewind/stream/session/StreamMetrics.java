package com.tradewind.stream.session;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Counters shared by all sessions of this instance. */
public final class StreamMetrics {

    final Counter sent;
    final Counter conflated;
    final Counter staleDropped;
    final Counter closedSlow;
    final Counter closedIdle;

    public StreamMetrics(MeterRegistry meters) {
        this.sent = Counter.builder("stream.messages.sent").register(meters);
        this.conflated = Counter.builder("stream.quotes.conflated")
                .description("Quotes replaced by a newer one within the conflation interval").register(meters);
        this.staleDropped = Counter.builder("stream.quotes.dropped")
                .description("Quotes replaced by a newer one while waiting for a slow client").register(meters);
        this.closedSlow = Counter.builder("stream.sessions.closed").tag("reason", "slow").register(meters);
        this.closedIdle = Counter.builder("stream.sessions.closed").tag("reason", "idle").register(meters);
    }

    public static StreamMetrics noop() {
        return new StreamMetrics(new SimpleMeterRegistry());
    }

    public long closedSlow() {
        return (long) closedSlow.count();
    }

    public long closedIdle() {
        return (long) closedIdle.count();
    }

    long staleDropped() {
        return (long) staleDropped.count();
    }
}
