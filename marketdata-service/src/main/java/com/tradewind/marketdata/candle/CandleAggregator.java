package com.tradewind.marketdata.candle;

import com.tradewind.common.money.Price;
import com.tradewind.common.quote.QuoteTick;
import com.tradewind.marketdata.simulator.SimulatedTick;
import com.tradewind.marketdata.simulator.TickSink;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Folds ticks into 1-minute candles in memory (simulator thread) and hands snapshots to {@link CandleFlusher}
 * (scheduler thread). Buckets are in simulated market time.
 */
@Component
public class CandleAggregator implements TickSink {

    /** Max updates kept for retry while Postgres is unavailable (about 1 hour of minutes for 500 symbols). */
    static final int MAX_PENDING = 30_000;

    private static final class Bucket {
        final Instant start;
        final Price open;
        Price high;
        Price low;
        Price close;
        long volumeDelta;
        boolean dirty;

        Bucket(Instant start, Price first) {
            this.start = start;
            this.open = first;
            this.high = first;
            this.low = first;
            this.close = first;
        }
    }

    private final Map<String, Bucket> current = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<CandleUpdate> pending = new ConcurrentLinkedQueue<>();

    @Override
    public void accept(List<SimulatedTick> batch) {
        for (SimulatedTick t : batch) {
            QuoteTick tick = t.tick();
            Instant bucketStart = tick.marketTime().truncatedTo(ChronoUnit.MINUTES);
            current.compute(tick.symbol(), (symbol, bucket) -> {
                if (bucket != null && !bucket.start.equals(bucketStart)) {
                    if (bucket.dirty) {
                        pending.add(snapshot(symbol, bucket));
                    }
                    bucket = null;
                }
                if (bucket == null) {
                    bucket = new Bucket(bucketStart, tick.last());
                }
                if (tick.last().compareTo(bucket.high) > 0) {
                    bucket.high = tick.last();
                }
                if (tick.last().compareTo(bucket.low) < 0) {
                    bucket.low = tick.last();
                }
                bucket.close = tick.last();
                bucket.volumeDelta += tick.volume();
                bucket.dirty = true;
                return bucket;
            });
        }
    }

    /** Completed buckets plus the in-progress state of open buckets, resetting their volume deltas. */
    public List<CandleUpdate> drain() {
        List<CandleUpdate> updates = new ArrayList<>();
        CandleUpdate next;
        while ((next = pending.poll()) != null) {
            updates.add(next);
        }
        for (String symbol : current.keySet()) {
            current.computeIfPresent(symbol, (s, bucket) -> {
                if (bucket.dirty) {
                    updates.add(snapshot(s, bucket));
                }
                return bucket;
            });
        }
        return updates;
    }

    /** Puts back updates whose flush failed; drops the oldest beyond {@link #MAX_PENDING}. */
    public void requeue(List<CandleUpdate> updates) {
        pending.addAll(updates);
        while (pending.size() > MAX_PENDING) {
            pending.poll();
        }
    }

    private static CandleUpdate snapshot(String symbol, Bucket bucket) {
        CandleUpdate update = new CandleUpdate(symbol, bucket.start, bucket.open, bucket.high, bucket.low,
                bucket.close, bucket.volumeDelta);
        bucket.volumeDelta = 0;
        bucket.dirty = false;
        return update;
    }
}
