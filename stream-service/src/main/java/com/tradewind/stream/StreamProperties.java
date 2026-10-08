package com.tradewind.stream;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.UUID;

/** WebSocket fan-out settings (spec 6.2). */
@ConfigurationProperties("tradewind.stream")
public record StreamProperties(
        /* Shown to clients in the hello message; defaults to the container hostname, else a random id. */
        @DefaultValue("") String instanceId,
        /* At most one update per session per symbol per interval; the latest value wins. */
        @DefaultValue("250ms") Duration conflationInterval,
        @DefaultValue("100") int maxSubscriptions,
        /* Non-quote messages (pong, errors, user events) waiting for a slow client before it is disconnected. */
        @DefaultValue("256") int sendQueueLimit,
        /* A client that cannot take its pending messages for this long is closed with code 4000. */
        @DefaultValue("10s") Duration slowClientTimeout,
        /* Clients ping at this interval (advertised in hello)... */
        @DefaultValue("15s") Duration heartbeatInterval,
        /* ...and a client silent for this long is closed with code 4001. */
        @DefaultValue("45s") Duration idleTimeout,
        /* After a close frame is queued, how long a client that never reads keeps its connection. */
        @DefaultValue("5s") Duration closeGrace,
        @DefaultValue("16384") int maxFrameBytes) {

    public StreamProperties {
        if (instanceId == null || instanceId.isBlank()) {
            instanceId = UUID.randomUUID().toString().substring(0, 12);
        }
        if (maxSubscriptions < 1 || sendQueueLimit < 1) {
            throw new IllegalArgumentException("maxSubscriptions and sendQueueLimit must be positive");
        }
    }
}
