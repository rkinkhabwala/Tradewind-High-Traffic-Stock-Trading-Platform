package com.tradewind.common.quote;

import com.tradewind.common.money.Price;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Redis layout of the latest quote (spec 5): hash {@code quote:{symbol}} with fields bid, ask, last, bidSize,
 * askSize, ts (market time, ISO-8601) and prevClose. Written by marketdata-service, read by any service.
 */
public final class QuoteHash {

    public record StoredQuote(QuoteTick tick, Price prevClose) {
    }

    private QuoteHash() {
    }

    /** Redis set of every tradable symbol, maintained by marketdata-service. */
    public static final String SYMBOLS_KEY = "symbols";

    public static String key(String symbol) {
        return "quote:" + symbol;
    }

    public static Map<String, String> toHash(QuoteTick tick, Price prevClose) {
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("bid", tick.bid().toJson());
        hash.put("ask", tick.ask().toJson());
        hash.put("last", tick.last().toJson());
        hash.put("bidSize", Long.toString(tick.bidSize()));
        hash.put("askSize", Long.toString(tick.askSize()));
        hash.put("ts", tick.marketTime().toString());
        hash.put("prevClose", prevClose.toJson());
        return hash;
    }

    /** Empty if the hash is missing or incomplete (e.g. symbol never ticked). */
    public static Optional<StoredQuote> fromHash(String symbol, Map<String, String> hash) {
        if (hash == null || !hash.keySet().containsAll(java.util.List.of("bid", "ask", "last", "bidSize", "askSize", "ts"))) {
            return Optional.empty();
        }
        Price last = Price.of(hash.get("last"));
        QuoteTick tick = new QuoteTick(symbol, Price.of(hash.get("bid")), Price.of(hash.get("ask")), last,
                Long.parseLong(hash.get("bidSize")), Long.parseLong(hash.get("askSize")), 0,
                Instant.parse(hash.get("ts")));
        Price prevClose = hash.containsKey("prevClose") ? Price.of(hash.get("prevClose")) : last;
        return Optional.of(new StoredQuote(tick, prevClose));
    }
}
