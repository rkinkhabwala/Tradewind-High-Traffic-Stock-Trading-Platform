package com.tradewind.gateway.ws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * One-time WebSocket tickets (approved Phase 2 decision 1). Browsers cannot send an Authorization header on a
 * WebSocket, and a JWT in the URL would end up in logs and history; instead an authenticated client exchanges its
 * JWT for a random ticket that lives 30 s in Redis and is deleted by the first {@code /ws?ticket=} that uses it.
 */
@Component
class WsTickets {

    record Identity(String userId, String accountId) {
    }

    static final String KEY_PREFIX = "ws-ticket:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ReactiveStringRedisTemplate redis;
    private final Duration ttl;

    WsTickets(ReactiveStringRedisTemplate redis, @Value("${tradewind.ws.ticket-ttl:30s}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    Duration ttl() {
        return ttl;
    }

    Mono<String> issue(String userId, String accountId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return redis.opsForValue().set(KEY_PREFIX + ticket, userId + "|" + accountId, ttl).thenReturn(ticket);
    }

    /** Atomically reads and deletes (GETDEL), so a ticket can never be used twice. Empty if unknown or expired. */
    Mono<Identity> redeem(String ticket) {
        return redis.opsForValue().getAndDelete(KEY_PREFIX + ticket).map(value -> {
            String[] parts = value.split("\\|", 2);
            return new Identity(parts[0], parts[1]);
        });
    }
}
