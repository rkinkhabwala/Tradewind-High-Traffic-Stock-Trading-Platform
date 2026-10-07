package com.tradewind.execution.quote;

import com.tradewind.common.quote.QuoteTick;

import java.util.Optional;

/** Latest quote for a symbol (spec 6.3: market orders fill against the current quote in Redis). */
public interface QuoteSource {

    /** Empty if there is no quote or the store is unreachable within its timeout. */
    Optional<QuoteTick> latest(String symbol);
}
