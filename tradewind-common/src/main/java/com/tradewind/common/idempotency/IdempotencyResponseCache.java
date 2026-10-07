package com.tradewind.common.idempotency;

import java.util.Optional;
import java.util.UUID;

/**
 * Fast path for completed idempotent responses (spec 4: "Postgres, with Redis as a fast path"). Purely an
 * optimisation: implementations must have short timeouts and may lose entries; Postgres stays authoritative.
 */
public interface IdempotencyResponseCache {

    record CachedResponse(String requestHash, StoredResponse response) {
    }

    Optional<CachedResponse> get(UUID accountId, String key);

    void put(UUID accountId, String key, CachedResponse response);
}
