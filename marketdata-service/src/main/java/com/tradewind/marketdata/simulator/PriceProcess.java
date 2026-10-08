package com.tradewind.marketdata.simulator;

import com.tradewind.common.money.Price;
import com.tradewind.common.quote.QuoteHash.StoredQuote;
import com.tradewind.marketdata.symbol.SymbolInfo;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.SplittableRandom;

/** Price state of one symbol. Only ever touched by the simulator thread. */
final class PriceProcess {

    final String symbol;
    private final double sigma;
    private final long meanIntervalNanos;
    private final SplittableRandom random;
    private double price;
    private Price lastPrice;
    private Price prevClose;
    private LocalDate sessionDate;
    private Instant lastMarketTime;
    private long nextTickNanos;

    PriceProcess(SymbolInfo info, double sigma, double ticksPerSecond, SplittableRandom random, StoredQuote stored,
                 long nowNanos) {
        this.symbol = info.symbol();
        this.sigma = sigma;
        this.meanIntervalNanos = (long) (1_000_000_000L / ticksPerSecond);
        this.random = random;
        if (stored != null) {
            // Resume where the last run stopped instead of jumping back to the seed price.
            this.lastPrice = stored.tick().last();
            this.prevClose = stored.prevClose();
            this.lastMarketTime = stored.tick().marketTime();
            this.sessionDate = stored.tick().marketTime().atZone(com.tradewind.common.clock.MarketHours.ZONE).toLocalDate();
        } else {
            this.lastPrice = info.basePrice();
            this.prevClose = info.basePrice();
        }
        this.price = lastPrice.value().doubleValue();
        this.nextTickNanos = nowNanos + (long) (meanIntervalNanos * random.nextDouble());
    }

    boolean due(long nowNanos) {
        return nowNanos >= nextTickNanos;
    }

    SimulatedTick tick(Instant marketNow, LocalDate session, Instant sessionOpen, long nowNanos, SimulatorProperties p) {
        return tick(marketNow, session, sessionOpen, nowNanos, p, 1);
    }

    /** {@code speedup} > 1 shortens the interval to the next tick (TickRate boost). */
    SimulatedTick tick(Instant marketNow, LocalDate session, Instant sessionOpen, long nowNanos, SimulatorProperties p,
                       double speedup) {
        if (sessionDate == null) {
            sessionDate = session;
        } else if (!session.equals(sessionDate)) {
            prevClose = lastPrice;      // first tick of a new session: yesterday's last becomes the previous close
            sessionDate = session;
        }
        // Only time while the market is open moves the price: no overnight drift while prices are frozen.
        Instant from = lastMarketTime == null || lastMarketTime.isBefore(sessionOpen) ? sessionOpen : lastMarketTime;
        double dtYears = Math.max(0, Duration.between(from, marketNow).toNanos() / 1e9) / GbmModel.TRADING_SECONDS_PER_YEAR;
        double jump = random.nextDouble() < p.jumpProbability() ? p.jumpVolatility() * random.nextGaussian() : 0;
        price = GbmModel.step(price, p.drift(), sigma, dtYears, random.nextGaussian(), jump);
        lastMarketTime = marketNow;
        // Schedule from the previous due time, not from "now": otherwise every tick loses up to one loop period
        // and fast symbols fall well short of their rate. After a long stall, restart instead of bursting.
        long base = nowNanos - nextTickNanos > 1_000_000_000L ? nowNanos : nextTickNanos;
        nextTickNanos = base + (long) (meanIntervalNanos * (0.5 + random.nextDouble()) / speedup);

        SimulatedTick tick = new SimulatedTick(QuoteFactory.quote(symbol, price, p.spreadBps(),
                100L * (1 + random.nextInt(20)), 100L * (1 + random.nextInt(20)), 1 + random.nextInt(500), marketNow),
                prevClose);
        lastPrice = tick.tick().last();
        return tick;
    }
}
