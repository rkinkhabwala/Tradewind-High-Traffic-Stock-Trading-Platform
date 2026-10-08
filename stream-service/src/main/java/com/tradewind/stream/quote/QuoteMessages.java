package com.tradewind.stream.quote;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradewind.common.event.EventJson;
import com.tradewind.common.quote.QuoteHash.StoredQuote;
import com.tradewind.common.quote.QuoteTick;

import java.io.UncheckedIOException;
import java.time.Instant;

/**
 * The {@code quote} server message. Prices are decimal strings, never floating point. {@code prevClose} is only on
 * the snapshot sent at subscribe time (ticks on Kafka do not carry it); clients keep it for the day-change display.
 * {@code publishedAt} is the wall-clock time marketdata-service published the tick (live quotes only), so clients
 * and load tests can measure tick-to-client latency; {@code ts} is simulated market time.
 */
public final class QuoteMessages {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record QuoteMessage(String type, String symbol, String bid, String ask, String last, long bidSize, long askSize,
                        String prevClose, Instant ts, Instant publishedAt) {
    }

    private static final ObjectMapper JSON = EventJson.newMapper();

    private QuoteMessages() {
    }

    public static String live(QuoteTick tick, Instant publishedAt) {
        return write(tick, null, publishedAt);
    }

    public static String snapshot(StoredQuote quote) {
        return write(quote.tick(), quote.prevClose().toJson(), null);
    }

    private static String write(QuoteTick t, String prevClose, Instant publishedAt) {
        try {
            return JSON.writeValueAsString(new QuoteMessage("quote", t.symbol(), t.bid().toJson(), t.ask().toJson(),
                    t.last().toJson(), t.bidSize(), t.askSize(), prevClose, t.marketTime(), publishedAt));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
