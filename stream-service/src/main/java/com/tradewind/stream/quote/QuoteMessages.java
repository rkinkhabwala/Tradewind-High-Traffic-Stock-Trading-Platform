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
 */
public final class QuoteMessages {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record QuoteMessage(String type, String symbol, String bid, String ask, String last, long bidSize, long askSize,
                        String prevClose, Instant ts) {
    }

    private static final ObjectMapper JSON = EventJson.newMapper();

    private QuoteMessages() {
    }

    public static String live(QuoteTick tick) {
        return write(tick, null);
    }

    public static String snapshot(StoredQuote quote) {
        return write(quote.tick(), quote.prevClose().toJson());
    }

    private static String write(QuoteTick t, String prevClose) {
        try {
            return JSON.writeValueAsString(new QuoteMessage("quote", t.symbol(), t.bid().toJson(), t.ask().toJson(),
                    t.last().toJson(), t.bidSize(), t.askSize(), prevClose, t.marketTime()));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
