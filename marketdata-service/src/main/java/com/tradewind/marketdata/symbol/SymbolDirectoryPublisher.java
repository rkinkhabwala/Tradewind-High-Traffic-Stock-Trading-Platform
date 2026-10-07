package com.tradewind.marketdata.symbol;

import com.tradewind.common.quote.QuoteHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes the symbol universe to the Redis set {@code symbols} so other services (order validation) can check a
 * symbol with one SISMEMBER. Idempotent; re-run periodically in case Redis was restarted.
 */
@Component
class SymbolDirectoryPublisher {

    private static final Logger log = LoggerFactory.getLogger(SymbolDirectoryPublisher.class);

    private final SymbolCatalog catalog;
    private final StringRedisTemplate redis;

    SymbolDirectoryPublisher(SymbolCatalog catalog, StringRedisTemplate redis) {
        this.catalog = catalog;
        this.redis = redis;
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "PT60S")
    void publish() {
        try {
            String[] symbols = catalog.all().stream().map(SymbolInfo::symbol).toArray(String[]::new);
            redis.opsForSet().add(QuoteHash.SYMBOLS_KEY, symbols);
        } catch (DataAccessException e) {
            log.warn("Could not publish symbol directory to Redis: {}", e.toString());
        }
    }
}
