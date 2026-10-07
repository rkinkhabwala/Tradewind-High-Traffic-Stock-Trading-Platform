package com.tradewind.order.marketdata;

import com.tradewind.common.quote.QuoteHash.StoredQuote;

import java.util.Optional;

/**
 * Symbol directory and latest quotes published by marketdata-service. Implementations must time out quickly;
 * failures surface as DataAccessException and become a retryable 503.
 */
public interface MarketDataReader {

    boolean isKnownSymbol(String symbol);

    Optional<StoredQuote> quote(String symbol);
}
