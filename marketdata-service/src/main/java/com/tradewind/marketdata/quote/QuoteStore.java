package com.tradewind.marketdata.quote;

import com.tradewind.common.quote.QuoteHash.StoredQuote;
import com.tradewind.marketdata.simulator.SimulatedTick;
import com.tradewind.marketdata.symbol.SymbolInfo;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Latest quote per symbol (Redis {@code quote:{symbol}}). */
public interface QuoteStore {

    void write(List<SimulatedTick> batch);

    /** Symbols without a quote are absent from the result. */
    Map<String, StoredQuote> read(Collection<String> symbols);

    /** For simulator start-up: an unreachable store means "start from seed prices", not a failed boot. */
    Map<String, StoredQuote> readAllSafely(Collection<SymbolInfo> symbols);
}
