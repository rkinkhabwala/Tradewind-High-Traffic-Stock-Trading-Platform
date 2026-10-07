package com.tradewind.common.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Answers retries of completed requests from the cache without touching Postgres; everything else (first
 * attempts, in-flight duplicates, cache misses or cache outages) goes to {@link JdbcIdempotentExecutor}.
 */
public class CachingIdempotentExecutor {

    private static final Logger log = LoggerFactory.getLogger(CachingIdempotentExecutor.class);

    private final JdbcIdempotentExecutor delegate;
    private final IdempotencyResponseCache cache;

    public CachingIdempotentExecutor(JdbcIdempotentExecutor delegate, IdempotencyResponseCache cache) {
        this.delegate = delegate;
        this.cache = cache;
    }

    public StoredResponse execute(UUID accountId, String key, String requestHash, Supplier<StoredResponse> operation) {
        Optional<IdempotencyResponseCache.CachedResponse> cached = safeGet(accountId, key);
        if (cached.isPresent()) {
            if (!cached.get().requestHash().equals(requestHash)) {
                throw new IdempotencyConflictException.KeyReused();
            }
            return cached.get().response();
        }
        StoredResponse response = delegate.execute(accountId, key, requestHash, operation);
        try {
            cache.put(accountId, key, new IdempotencyResponseCache.CachedResponse(requestHash, response));
        } catch (RuntimeException e) {
            log.debug("Idempotency cache put failed: {}", e.toString());
        }
        return response;
    }

    private Optional<IdempotencyResponseCache.CachedResponse> safeGet(UUID accountId, String key) {
        try {
            return cache.get(accountId, key);
        } catch (RuntimeException e) {
            log.debug("Idempotency cache get failed, using Postgres: {}", e.toString());
            return Optional.empty();
        }
    }
}
