package com.tradewind.marketdata.simulator;

import com.tradewind.common.clock.MarketClock;
import com.tradewind.common.money.Price;
import com.tradewind.common.quote.QuoteHash.StoredQuote;
import com.tradewind.common.quote.QuoteTick;
import com.tradewind.marketdata.symbol.SymbolInfo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MarketSimulatorTest {

    private static final Instant MONDAY_10AM = Instant.parse("2026-01-05T15:00:00Z");
    private static final Instant SATURDAY_NOON = Instant.parse("2026-01-10T17:00:00Z");
    private static final long SECOND = 1_000_000_000L;

    private final AtomicReference<Instant> marketNow = new AtomicReference<>(MONDAY_10AM);
    private final MarketClock clock = marketNow::get;
    private final List<SimulatedTick> received = new ArrayList<>();

    private static List<SymbolInfo> symbols(String... tickers) {
        List<SymbolInfo> list = new ArrayList<>();
        for (String t : tickers) {
            list.add(new SymbolInfo(t, t + " Inc.", "Industrials", Price.of("100"), new BigDecimal("0.30")));
        }
        return list;
    }

    private static SimulatorProperties properties(int min, int max, String hot) {
        return new SimulatorProperties(true, min, max, hot, 100, 5, 0.05, 4, 0.0005, 0.02, 42,
                Duration.ofMillis(5), Duration.ofSeconds(5), 1000);
    }

    /** Drives the loop every 5 ms of fake time for {@code seconds}, advancing market time in step. */
    private void run(MarketSimulator simulator, double seconds) {
        long step = 5_000_000L;
        for (long t = 0; t <= (long) (seconds * SECOND); t += step) {
            marketNow.updateAndGet(i -> i.plusNanos(step));
            simulator.runOnce(t);
        }
    }

    @Test
    void ticksAtConfiguredRatesAndHotSymbolTicksAt100PerSecond() {
        MarketSimulator simulator = new MarketSimulator(symbols("GME", "AAPL", "MSFT"), Map.of(), clock,
                List.of(received::addAll), properties(1, 10, "GME"), 0);

        run(simulator, 10);

        Map<String, Long> perSymbol = received.stream()
                .collect(Collectors.groupingBy(t -> t.tick().symbol(), Collectors.counting()));
        assertThat(perSymbol.get("GME")).isBetween(900L, 1_100L);
        assertThat(perSymbol.get("AAPL")).isBetween(5L, 110L);
        assertThat(perSymbol.get("MSFT")).isBetween(5L, 110L);
    }

    @Test
    void noTicksWhileTheMarketIsClosed() {
        marketNow.set(SATURDAY_NOON);
        MarketSimulator simulator = new MarketSimulator(symbols("AAPL"), Map.of(), clock,
                List.of(received::addAll), properties(10, 10, ""), 0);

        run(simulator, 2);

        assertThat(received).isEmpty();
    }

    @Test
    void everyTickIsAValidQuoteAndPricesMove() {
        MarketSimulator simulator = new MarketSimulator(symbols("AAPL"), Map.of(), clock,
                List.of(received::addAll), properties(10, 10, ""), 0);

        run(simulator, 5);

        assertThat(received).hasSizeGreaterThan(20);
        assertThat(received).allSatisfy(t -> {
            QuoteTick q = t.tick();
            assertThat(q.bid()).isLessThan(q.ask());
            assertThat(q.bidSize()).isPositive();
            assertThat(t.prevClose()).isEqualTo(Price.of("100"));
        });
        assertThat(received.stream().map(t -> t.tick().last()).distinct().count()).isGreaterThan(1);
    }

    @Test
    void resumesFromTheStoredQuoteAndRollsPrevCloseAtTheNextSession() {
        QuoteTick yesterday = new QuoteTick("AAPL", Price.of("149.99"), Price.of("150.01"), Price.of("150.00"),
                100, 100, 0, Instant.parse("2026-01-02T20:59:00Z"));   // Friday 15:59 New York
        MarketSimulator simulator = new MarketSimulator(symbols("AAPL"),
                Map.of("AAPL", new StoredQuote(yesterday, Price.of("140.00"))), clock,
                List.of(received::addAll), properties(10, 10, ""), 0);

        run(simulator, 1);

        SimulatedTick first = received.getFirst();
        assertThat(first.prevClose()).isEqualTo(Price.of("150.00"));
        assertThat(first.tick().last().value()).isBetween(new BigDecimal("140"), new BigDecimal("160"));
    }

    @Test
    void failingSinkDoesNotStopOtherSinks() {
        MarketSimulator simulator = new MarketSimulator(symbols("AAPL"), Map.of(), clock,
                List.of(batch -> {
                    throw new IllegalStateException("redis down");
                }, received::addAll), properties(10, 10, ""), 0);

        run(simulator, 1);

        assertThat(received).isNotEmpty();
    }
}
