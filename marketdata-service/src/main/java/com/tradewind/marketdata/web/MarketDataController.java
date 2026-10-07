package com.tradewind.marketdata.web;

import com.tradewind.common.money.Price;
import com.tradewind.common.quote.QuoteHash.StoredQuote;
import com.tradewind.common.quote.QuoteTick;
import com.tradewind.marketdata.candle.CandleQueryService;
import com.tradewind.marketdata.quote.QuoteStore;
import com.tradewind.marketdata.symbol.SymbolCatalog;
import com.tradewind.marketdata.symbol.SymbolInfo;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Public market-data endpoints (spec 7), served through the gateway. Prices are plain decimal strings. */
@RestController
class MarketDataController {

    static final int MAX_QUOTES_PER_REQUEST = 100;
    static final int SEARCH_LIMIT = 20;

    record QuoteView(String symbol, String bid, String ask, String last, long bidSize, long askSize, Instant ts) {
        static QuoteView of(QuoteTick t) {
            return new QuoteView(t.symbol(), t.bid().toJson(), t.ask().toJson(), t.last().toJson(),
                    t.bidSize(), t.askSize(), t.marketTime());
        }
    }

    record SymbolView(String symbol, String name, String sector, String last, String prevClose, String dayChange,
                      String dayChangePercent, Instant ts) {
    }

    record SearchResult(String symbol, String name, String sector) {
    }

    private final SymbolCatalog catalog;
    private final QuoteStore quotes;
    private final CandleQueryService candles;

    MarketDataController(SymbolCatalog catalog, QuoteStore quotes, CandleQueryService candles) {
        this.catalog = catalog;
        this.quotes = quotes;
        this.candles = candles;
    }

    /** Unknown symbols, and symbols that have not ticked yet, are omitted. */
    @GetMapping("/api/quotes")
    List<QuoteView> quotes(@RequestParam String symbols) {
        List<String> requested = Arrays.stream(symbols.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        if (requested.isEmpty() || requested.size() > MAX_QUOTES_PER_REQUEST) {
            throw new IllegalArgumentException("symbols must list 1-" + MAX_QUOTES_PER_REQUEST + " symbols");
        }
        List<String> known = requested.stream().filter(s -> catalog.find(s).isPresent()).toList();
        if (known.isEmpty()) {
            return List.of();
        }
        return quotes.read(known).values().stream().map(q -> QuoteView.of(q.tick())).toList();
    }

    @GetMapping("/api/symbols/{symbol}")
    SymbolView symbol(@PathVariable String symbol) {
        SymbolInfo info = catalog.require(symbol);
        StoredQuote quote = quotes.read(List.of(info.symbol())).get(info.symbol());
        Price last = quote != null ? quote.tick().last() : info.basePrice();
        Price prevClose = quote != null ? quote.prevClose() : info.basePrice();
        BigDecimal change = last.value().subtract(prevClose.value());
        BigDecimal percent = change.multiply(BigDecimal.valueOf(100))
                .divide(prevClose.value(), 2, RoundingMode.HALF_EVEN);
        return new SymbolView(info.symbol(), info.name(), info.sector(), last.toJson(), prevClose.toJson(),
                change.setScale(2, RoundingMode.HALF_EVEN).toPlainString(), percent.toPlainString(),
                quote != null ? quote.tick().marketTime() : null);
    }

    @GetMapping("/api/symbols/{symbol}/candles")
    List<CandleQueryService.Candle> candles(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1m") String interval,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        SymbolInfo info = catalog.require(symbol);
        return candles.candles(info.symbol(), CandleQueryService.Interval.parse(interval), from, to);
    }

    @GetMapping("/api/search")
    List<SearchResult> search(@RequestParam(defaultValue = "") String q) {
        return catalog.search(q, SEARCH_LIMIT).stream()
                .map(s -> new SearchResult(s.symbol(), s.name(), s.sector()))
                .toList();
    }
}
