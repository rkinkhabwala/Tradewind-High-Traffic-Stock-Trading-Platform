package com.tradewind.marketdata.simulator;

import com.tradewind.common.money.Price;
import com.tradewind.common.quote.QuoteTick;

import java.time.Instant;

/** Turns a simulated mid price into a quote on the $0.01 tick: bid < ask, bid <= last <= ask, spread >= 1 cent. */
final class QuoteFactory {

    private QuoteFactory() {
    }

    static QuoteTick quote(String symbol, double mid, double spreadBps, long bidSize, long askSize, long volume,
                           Instant marketTime) {
        long midCents = Math.max(2, Math.round(mid * 100));
        long spreadCents = Math.max(1, Math.round(midCents * spreadBps / 10_000.0));
        long bidCents = Math.max(1, midCents - spreadCents / 2);
        long askCents = bidCents + spreadCents;
        long lastCents = Math.min(Math.max(midCents, bidCents), askCents);
        return new QuoteTick(symbol, Price.ofCents(bidCents), Price.ofCents(askCents), Price.ofCents(lastCents),
                bidSize, askSize, volume, marketTime);
    }
}
