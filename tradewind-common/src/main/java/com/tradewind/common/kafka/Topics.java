package com.tradewind.common.kafka;

import java.util.List;
import java.util.Map;

/** Kafka topics and partition counts (spec section 8). */
public final class Topics {

    /** Keyed by symbol. */
    public static final TopicSpec QUOTES_TICKS = new TopicSpec("quotes.ticks", 12);
    /** Keyed by symbol; carries OrderAccepted and CancelRequested so one consumer sees both in order. */
    public static final TopicSpec ORDERS_SUBMITTED = new TopicSpec("orders.submitted", 12);
    /** Keyed by account id; carries ExecutionReported, OrderCanceled, OrderExpired, CancelRejected. */
    public static final TopicSpec EXECUTIONS = new TopicSpec("executions", 12);
    /** Keyed by account id; portfolio-service's outgoing events. */
    public static final TopicSpec LEDGER_EVENTS = new TopicSpec("ledger.events", 12);

    public static final List<TopicSpec> ALL = List.of(QUOTES_TICKS, ORDERS_SUBMITTED, EXECUTIONS, LEDGER_EVENTS);

    /** Ticks are only useful for minutes; the rest keep the broker default. */
    public static Map<String, String> configFor(TopicSpec topic) {
        return topic.equals(QUOTES_TICKS) ? Map.of("retention.ms", Long.toString(60 * 60 * 1000L)) : Map.of();
    }

    private Topics() {
    }
}
