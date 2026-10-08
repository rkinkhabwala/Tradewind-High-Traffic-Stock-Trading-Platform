package com.tradewind.marketdata.simulator;

import com.tradewind.common.clock.MarketClock;
import com.tradewind.common.clock.MarketHours;
import com.tradewind.common.quote.QuoteHash.StoredQuote;
import com.tradewind.marketdata.symbol.SymbolInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Advances every symbol's price process. {@link #runOnce} is called in a loop by {@link SimulatorRunner}
 * (and directly by tests). Ticks are only produced while the simulated market is open.
 */
public class MarketSimulator {

    private static final Logger log = LoggerFactory.getLogger(MarketSimulator.class);

    private final List<PriceProcess> processes;
    private final MarketClock clock;
    private final List<TickSink> sinks;
    private final SimulatorProperties properties;
    private final TickRate rate;

    public MarketSimulator(Collection<SymbolInfo> symbols, Map<String, StoredQuote> stored, MarketClock clock,
                           List<TickSink> sinks, SimulatorProperties properties, long nowNanos) {
        this(symbols, stored, clock, sinks, properties, nowNanos, new TickRate());
    }

    public MarketSimulator(Collection<SymbolInfo> symbols, Map<String, StoredQuote> stored, MarketClock clock,
                           List<TickSink> sinks, SimulatorProperties properties, long nowNanos, TickRate tickRate) {
        this.rate = tickRate;
        this.clock = clock;
        this.sinks = List.copyOf(sinks);
        this.properties = properties;
        SplittableRandom root = properties.seed() == 0 ? new SplittableRandom() : new SplittableRandom(properties.seed());
        List<PriceProcess> list = new ArrayList<>();
        int rank = 0;
        for (SymbolInfo symbol : symbols) {
            boolean hot = properties.hotModeFor(symbol.symbol());
            double sigma = symbol.volatility().doubleValue() * (hot ? properties.hotVolatilityMultiplier() : 1);
            double rate = hot ? properties.hotTickRate() : rateFor(rank++);
            list.add(new PriceProcess(symbol, sigma, rate, root.split(), stored.get(symbol.symbol()), nowNanos));
        }
        this.processes = List.copyOf(list);
        log.info("Simulating {} symbols at {}-{} ticks/s{}", processes.size(), properties.tickRateMin(),
                properties.tickRateMax(), properties.hotSymbol().isBlank() ? "" : ", hot symbol " + properties.hotSymbol());
    }

    /** Produces the ticks that are due at {@code nowNanos} and hands them to every sink. Returns the tick count. */
    public int runOnce(long nowNanos) {
        if (!clock.isMarketOpen()) {
            return 0;
        }
        ZonedDateTime marketTime = clock.marketTime();
        Instant now = marketTime.toInstant();
        LocalDate session = marketTime.withZoneSameInstant(MarketHours.ZONE).toLocalDate();
        Instant sessionOpen = session.atTime(MarketHours.OPEN).atZone(MarketHours.ZONE).toInstant();

        double speedup = rate.multiplier(nowNanos);
        List<SimulatedTick> batch = new ArrayList<>();
        for (PriceProcess process : processes) {
            if (process.due(nowNanos)) {
                batch.add(process.tick(now, session, sessionOpen, nowNanos, properties, speedup));
            }
        }
        if (!batch.isEmpty()) {
            for (TickSink sink : sinks) {
                try {
                    sink.accept(batch);
                } catch (RuntimeException e) {
                    log.warn("Tick sink {} failed: {}", sink.getClass().getSimpleName(), e.toString());
                }
            }
        }
        return batch.size();
    }

    /**
     * Spreads symbols evenly over [min, max] ticks per second by their rank, so the total is predictable:
     * 500 symbols at 1-10/s average 5.5/s, about 2,750 ticks/s (a name hash skewed this by ~15%).
     */
    private double rateFor(int rank) {
        int span = properties.tickRateMax() - properties.tickRateMin() + 1;
        return properties.tickRateMin() + rank % span;
    }
}
