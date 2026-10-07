package com.tradewind.marketdata.simulator;

import com.tradewind.common.money.Price;
import com.tradewind.common.quote.QuoteTick;

/** A tick plus the symbol's previous session close, which the quote cache stores for day-change. */
public record SimulatedTick(QuoteTick tick, Price prevClose) {
}
