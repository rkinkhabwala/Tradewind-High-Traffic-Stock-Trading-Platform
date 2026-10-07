package com.tradewind.marketdata.candle;

import com.tradewind.common.money.Price;

import java.time.Instant;

/**
 * State of one 1-minute bucket to merge into Postgres. {@code volumeDelta} is the volume since the previous flush
 * of this bucket, so repeated flushes (and flushes after a restart) add up instead of overwriting.
 */
public record CandleUpdate(String symbol, Instant bucketStart, Price open, Price high, Price low, Price close,
                           long volumeDelta) {
}
