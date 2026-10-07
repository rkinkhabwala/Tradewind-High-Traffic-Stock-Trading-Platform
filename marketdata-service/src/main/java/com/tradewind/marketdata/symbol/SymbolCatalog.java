package com.tradewind.marketdata.symbol;

import com.tradewind.common.money.Price;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** The symbol universe is static seed data, so it is loaded once and served from memory. */
@Component
public class SymbolCatalog {

    public static class NoSuchSymbolException extends RuntimeException {
        public NoSuchSymbolException(String symbol) {
            super("Unknown symbol: " + symbol);
        }
    }

    private final Map<String, SymbolInfo> bySymbol;

    @Autowired
    public SymbolCatalog(JdbcClient jdbc) {
        this(jdbc.sql("SELECT symbol, name, sector, base_price, volatility FROM symbols ORDER BY symbol")
                .query((rs, n) -> new SymbolInfo(rs.getString("symbol"), rs.getString("name"), rs.getString("sector"),
                        Price.of(rs.getBigDecimal("base_price")), rs.getBigDecimal("volatility")))
                .list());
    }

    public SymbolCatalog(List<SymbolInfo> symbols) {
        Map<String, SymbolInfo> map = new LinkedHashMap<>();
        symbols.forEach(s -> map.put(s.symbol(), s));
        this.bySymbol = Map.copyOf(map);
    }

    public Collection<SymbolInfo> all() {
        return bySymbol.values();
    }

    public Optional<SymbolInfo> find(String symbol) {
        return Optional.ofNullable(bySymbol.get(symbol.toUpperCase(Locale.ROOT)));
    }

    public SymbolInfo require(String symbol) {
        return find(symbol).orElseThrow(() -> new NoSuchSymbolException(symbol));
    }

    /**
     * Prefix search: exact ticker first, then ticker prefixes, then names with a word starting with {@code q}.
     */
    public List<SymbolInfo> search(String query, int limit) {
        String q = query == null ? "" : query.trim().toUpperCase(Locale.ROOT);
        if (q.isEmpty()) {
            return List.of();
        }
        List<SymbolInfo> tickerMatches = new ArrayList<>();
        List<SymbolInfo> nameMatches = new ArrayList<>();
        for (SymbolInfo s : bySymbol.values()) {
            if (s.symbol().startsWith(q)) {
                tickerMatches.add(s);
            } else if (nameHasWordStartingWith(s.name(), q)) {
                nameMatches.add(s);
            }
        }
        tickerMatches.sort(Comparator.comparing((SymbolInfo s) -> !s.symbol().equals(q))
                .thenComparing(s -> s.symbol().length()).thenComparing(SymbolInfo::symbol));
        nameMatches.sort(Comparator.comparing(SymbolInfo::name));
        List<SymbolInfo> result = new ArrayList<>(tickerMatches);
        result.addAll(nameMatches);
        return result.subList(0, Math.min(limit, result.size()));
    }

    private static boolean nameHasWordStartingWith(String name, String q) {
        for (String word : name.toUpperCase(Locale.ROOT).split("[\\s.,&-]+")) {
            if (word.startsWith(q)) {
                return true;
            }
        }
        return false;
    }
}
