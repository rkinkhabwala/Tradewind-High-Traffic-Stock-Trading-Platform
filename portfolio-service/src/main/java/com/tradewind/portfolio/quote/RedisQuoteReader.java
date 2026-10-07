package com.tradewind.portfolio.quote;

import com.tradewind.common.quote.QuoteHash;
import com.tradewind.common.quote.QuoteHash.StoredQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One pipelined round trip; times out after spring.data.redis.timeout. */
@Component
class RedisQuoteReader implements QuoteReader {

    private static final Logger log = LoggerFactory.getLogger(RedisQuoteReader.class);

    private final StringRedisTemplate redis;

    RedisQuoteReader(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, StoredQuote> latest(Collection<String> symbols) {
        if (symbols.isEmpty()) {
            return Map.of();
        }
        List<String> ordered = List.copyOf(symbols);
        try {
            List<Object> hashes = redis.executePipelined((RedisCallback<Object>) connection -> {
                ordered.forEach(s -> ((StringRedisConnection) connection).hGetAll(QuoteHash.key(s)));
                return null;
            });
            Map<String, StoredQuote> result = new HashMap<>();
            for (int i = 0; i < ordered.size(); i++) {
                String symbol = ordered.get(i);
                QuoteHash.fromHash(symbol, (Map<String, String>) hashes.get(i)).ifPresent(q -> result.put(symbol, q));
            }
            return result;
        } catch (DataAccessException e) {
            log.warn("Quote lookup failed, serving portfolio without live prices: {}", e.toString());
            return Map.of();
        }
    }
}
