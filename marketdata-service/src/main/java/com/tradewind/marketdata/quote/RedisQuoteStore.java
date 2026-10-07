package com.tradewind.marketdata.quote;

import com.tradewind.common.quote.QuoteHash;
import com.tradewind.common.quote.QuoteHash.StoredQuote;
import com.tradewind.marketdata.simulator.SimulatedTick;
import com.tradewind.marketdata.symbol.SymbolInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One pipelined round trip per batch, for both writes and multi-symbol reads. */
@Component
class RedisQuoteStore implements QuoteStore {

    private static final Logger log = LoggerFactory.getLogger(RedisQuoteStore.class);

    private final StringRedisTemplate redis;

    RedisQuoteStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void write(List<SimulatedTick> batch) {
        redis.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection strings = (StringRedisConnection) connection;
            for (SimulatedTick t : batch) {
                strings.hMSet(QuoteHash.key(t.tick().symbol()), QuoteHash.toHash(t.tick(), t.prevClose()));
            }
            return null;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, StoredQuote> read(Collection<String> symbols) {
        List<String> ordered = List.copyOf(symbols);
        List<Object> hashes = redis.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection strings = (StringRedisConnection) connection;
            ordered.forEach(symbol -> strings.hGetAll(QuoteHash.key(symbol)));
            return null;
        });
        Map<String, StoredQuote> result = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            String symbol = ordered.get(i);
            QuoteHash.fromHash(symbol, (Map<String, String>) hashes.get(i)).ifPresent(q -> result.put(symbol, q));
        }
        return result;
    }

    @Override
    public Map<String, StoredQuote> readAllSafely(Collection<SymbolInfo> symbols) {
        try {
            return read(symbols.stream().map(SymbolInfo::symbol).toList());
        } catch (DataAccessException e) {
            log.warn("Could not read stored quotes, starting from seed prices: {}", e.toString());
            return Map.of();
        }
    }
}
