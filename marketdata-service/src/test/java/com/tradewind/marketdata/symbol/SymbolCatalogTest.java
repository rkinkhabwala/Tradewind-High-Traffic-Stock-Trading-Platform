package com.tradewind.marketdata.symbol;

import com.tradewind.common.money.Price;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SymbolCatalogTest {

    private static SymbolInfo s(String symbol, String name) {
        return new SymbolInfo(symbol, name, "Information Technology", Price.of("10"), new BigDecimal("0.3"));
    }

    private final SymbolCatalog catalog = new SymbolCatalog(List.of(
            s("AAPL", "Apple Inc."), s("AA", "Alcoa Corp."), s("APP", "Applovin Corp."),
            s("MSFT", "Microsoft Corp."), s("XYZ", "Pineapple Foods Co.")));

    @Test
    void exactTickerFirstThenShorterTickerPrefixesThenNameWords() {
        assertThat(catalog.search("aa", 10)).extracting(SymbolInfo::symbol).containsExactly("AA", "AAPL");
        assertThat(catalog.search("app", 10)).extracting(SymbolInfo::symbol).containsExactly("APP", "AAPL");
        assertThat(catalog.search("micro", 10)).extracting(SymbolInfo::symbol).containsExactly("MSFT");
    }

    @Test
    void nameMatchesWholeWordPrefixesOnly() {
        // "Pineapple" contains "apple" but does not start with it.
        assertThat(catalog.search("apple", 10)).extracting(SymbolInfo::symbol).containsExactly("AAPL");
    }

    @Test
    void blankQueriesAndLimits() {
        assertThat(catalog.search("  ", 10)).isEmpty();
        assertThat(catalog.search("a", 1)).hasSize(1);
    }

    @Test
    void lookupIsCaseInsensitive() {
        assertThat(catalog.require("msft").name()).isEqualTo("Microsoft Corp.");
        assertThatThrownBy(() -> catalog.require("NOPE")).isInstanceOf(SymbolCatalog.NoSuchSymbolException.class);
    }
}
