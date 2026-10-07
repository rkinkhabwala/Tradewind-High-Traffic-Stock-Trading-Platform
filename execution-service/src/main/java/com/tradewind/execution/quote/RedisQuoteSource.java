package com.tradewind.execution.quote;

import com.tradewind.common.quote.QuoteHash;
import com.tradewind.common.quote.QuoteTick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Redis {@code quote:{symbol}}; times out after spring.data.redis.timeout (500ms). */
@Component
class RedisQuoteSource implements QuoteSource {

    private static final Logger log = LoggerFactory.getLogger(RedisQuoteSource.class);

    private final StringRedisTemplate redis;

    RedisQuoteSource(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<QuoteTick> latest(String symbol) {
        try {
            return QuoteHash.fromHash(symbol, redis.<String, String>opsForHash().entries(QuoteHash.key(symbol)))
                    .map(QuoteHash.StoredQuote::tick);
        } catch (DataAccessException e) {
            log.warn("Quote lookup for {} failed: {}", symbol, e.toString());
            return Optional.empty();
        }
    }
}
