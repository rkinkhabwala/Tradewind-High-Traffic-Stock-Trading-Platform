package com.tradewind.marketdata.simulator;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Decouples a slow or failing sink (Redis, Kafka) from the simulator thread with a bounded queue. When the queue
 * is full the batch is dropped and counted: quotes are superseded within milliseconds, so a stale backlog is worse
 * than a gap.
 */
public final class AsyncTickSink implements TickSink, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AsyncTickSink.class);

    private final TickSink delegate;
    private final BlockingQueue<List<SimulatedTick>> queue;
    private final Counter dropped;
    private final Thread worker;

    public AsyncTickSink(String name, TickSink delegate, int capacity, MeterRegistry meters) {
        this.delegate = delegate;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.dropped = Counter.builder("marketdata.ticks.dropped").tag("sink", name).register(meters);
        this.worker = Thread.ofPlatform().name("tick-sink-" + name).daemon().start(this::drain);
    }

    @Override
    public void accept(List<SimulatedTick> batch) {
        if (!queue.offer(batch)) {
            dropped.increment(batch.size());
        }
    }

    private void drain() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                delegate.accept(queue.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.warn("Tick sink {} failed: {}", worker.getName(), e.toString());
            }
        }
    }

    @Override
    public void close() {
        worker.interrupt();
    }
}
