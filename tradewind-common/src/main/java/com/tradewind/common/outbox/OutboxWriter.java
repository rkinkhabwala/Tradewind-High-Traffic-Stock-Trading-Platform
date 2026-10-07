package com.tradewind.common.outbox;

import com.tradewind.common.event.EventEnvelope;
import com.tradewind.common.event.EventJson;
import com.tradewind.common.kafka.TopicSpec;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;

/**
 * Transactional outbox (spec section 4): the event row is written in the caller's database transaction, together
 * with the state change it describes, and published later by {@link OutboxRelay}. Expects:
 * <pre>
 * outbox(id BIGSERIAL PK, event_id UUID UNIQUE, aggregate_id VARCHAR, event_type VARCHAR, topic VARCHAR,
 *        event_key VARCHAR, payload_json TEXT, created_at TIMESTAMPTZ, published_at TIMESTAMPTZ NULL)
 * </pre>
 * Must be called inside a transaction; on its own it would be exactly the "write then publish" it exists to prevent.
 */
public class OutboxWriter {

    private final JdbcClient jdbc;

    public OutboxWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void append(TopicSpec topic, String aggregateId, EventEnvelope<?> event) {
        jdbc.sql("""
                        INSERT INTO outbox (event_id, aggregate_id, event_type, topic, event_key, payload_json, created_at)
                        VALUES (:eventId, :aggregate, :type, :topic, :key, :payload, :created)""")
                .param("eventId", event.eventId())
                .param("aggregate", aggregateId)
                .param("type", event.eventType())
                .param("topic", topic.name())
                .param("key", event.key())
                .param("payload", EventJson.write(event))
                .param("created", Timestamp.from(event.occurredAt()))
                .update();
    }
}
