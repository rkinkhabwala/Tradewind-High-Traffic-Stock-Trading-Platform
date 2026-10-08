package com.tradewind.stream.quote;

import com.tradewind.common.quote.QuoteHash;
import com.tradewind.common.quote.QuoteHash.StoredQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Latest stored quotes and the tradable-symbol set, read from Redis (written by marketdata-service). */
@Component
public class QuoteSnapshots {

    private static final Logger log = LoggerFactory.getLogger(QuoteSnapshots.class);
    static final Duration TIMEOUT = Duration.ofSeconds(1);
    static final Duration SYMBOLS_TTL = Duration.ofSeconds(30);

    private final ReactiveStringRedisTemplate redis;
    private final AtomicReference<Known> known = new AtomicReference<>(new Known(Set.of(), 0));

    private record Known(Set<String> symbols, long loadedAt) {
    }

    QuoteSnapshots(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Stored quotes for the symbols that have one. Best effort: on a Redis error, the next tick fills in. */
    public Flux<StoredQuote> latest(Collection<String> symbols) {
        return Flux.fromIterable(symbols)
                .flatMap(symbol -> redis.<String, String>opsForHash().entries(QuoteHash.key(symbol))
                        .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                        .flatMap(hash -> Mono.justOrEmpty(QuoteHash.fromHash(symbol, hash))))
                .timeout(TIMEOUT)
                .onErrorResume(e -> {
                    log.warn("Could not read stored quotes: {}", e.toString());
                    return Flux.empty();
                });
    }

    /**
     * The tradable symbols, cached for 30s. Empty if Redis has none or cannot be reached; callers then accept any
     * well-formed symbol rather than rejecting valid subscriptions.
     */
    public Mono<Set<String>> knownSymbols() {
        Known current = known.get();
        if (!current.symbols().isEmpty() && System.currentTimeMillis() - current.loadedAt() < SYMBOLS_TTL.toMillis()) {
            return Mono.just(current.symbols());
        }
        return redis.opsForSet().members(QuoteHash.SYMBOLS_KEY).collect(java.util.stream.Collectors.toUnmodifiableSet())
                .timeout(TIMEOUT)
                .doOnNext(symbols -> known.set(new Known(symbols, System.currentTimeMillis())))
                .onErrorResume(e -> Mono.just(current.symbols()));
    }
}
