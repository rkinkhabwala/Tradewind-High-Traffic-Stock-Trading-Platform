package com.tradewind.portfolio.quote;

import com.tradewind.common.quote.QuoteHash.StoredQuote;

import java.util.Collection;
import java.util.Map;

/** Latest quotes for valuing positions. Empty map (not an error) when the quote store is unavailable. */
public interface QuoteReader {

    Map<String, StoredQuote> latest(Collection<String> symbols);
}
