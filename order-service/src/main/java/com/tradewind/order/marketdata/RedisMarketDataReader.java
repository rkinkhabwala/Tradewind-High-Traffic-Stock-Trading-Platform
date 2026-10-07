package com.tradewind.order.marketdata;

import com.tradewind.common.quote.QuoteHash;
import com.tradewind.common.quote.QuoteHash.StoredQuote;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Redis calls time out after spring.data.redis.timeout (500ms). */
@Component
class RedisMarketDataReader implements MarketDataReader {

    private final StringRedisTemplate redis;

    RedisMarketDataReader(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean isKnownSymbol(String symbol) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(QuoteHash.SYMBOLS_KEY, symbol));
    }

    @Override
    public Optional<StoredQuote> quote(String symbol) {
        return QuoteHash.fromHash(symbol, redis.<String, String>opsForHash().entries(QuoteHash.key(symbol)));
    }
}
