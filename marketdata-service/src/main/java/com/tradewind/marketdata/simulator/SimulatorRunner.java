package com.tradewind.marketdata.simulator;

import com.tradewind.common.clock.MarketClock;
import com.tradewind.marketdata.candle.CandleAggregator;
import com.tradewind.marketdata.quote.KafkaTickPublisher;
import com.tradewind.marketdata.quote.QuoteStore;
import com.tradewind.marketdata.symbol.SymbolCatalog;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs the simulator loop on one dedicated thread. Sinks: Redis quotes and Kafka ticks (each behind its own
 * bounded queue) and the in-memory candle aggregator (inline, no I/O).
 */
@Component
class SimulatorRunner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SimulatorRunner.class);

    private final SimulatorProperties properties;
    private final SymbolCatalog catalog;
    private final QuoteStore quotes;
    private final KafkaTickPublisher kafka;
    private final CandleAggregator candles;
    private final MarketClock clock;
    private final MeterRegistry meters;
    private ScheduledExecutorService loop;
    private List<AsyncTickSink> asyncSinks = List.of();
    private volatile boolean running;

    SimulatorRunner(SimulatorProperties properties, SymbolCatalog catalog, QuoteStore quotes, KafkaTickPublisher kafka,
                    CandleAggregator candles, MarketClock clock, MeterRegistry meters) {
        this.properties = properties;
        this.catalog = catalog;
        this.quotes = quotes;
        this.kafka = kafka;
        this.candles = candles;
        this.clock = clock;
        this.meters = meters;
    }

    @Override
    public void start() {
        if (!properties.enabled()) {
            log.info("Price simulator disabled");
            return;
        }
        asyncSinks = List.of(
                new AsyncTickSink("redis", quotes::write, properties.sinkQueueCapacity(), meters),
                new AsyncTickSink("kafka", kafka, properties.sinkQueueCapacity(), meters));
        MarketSimulator simulator = new MarketSimulator(catalog.all(), quotes.readAllSafely(catalog.all()), clock,
                List.of(asyncSinks.get(0), asyncSinks.get(1), candles), properties, System.nanoTime());
        Counter produced = Counter.builder("marketdata.ticks.produced").register(meters);
        loop = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("price-simulator").daemon().factory());
        loop.scheduleWithFixedDelay(() -> {
            try {
                produced.increment(simulator.runOnce(System.nanoTime()));
            } catch (RuntimeException e) {
                log.error("Simulator iteration failed", e);
            }
        }, 0, properties.loopInterval().toNanos(), TimeUnit.NANOSECONDS);
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (loop != null) {
            loop.shutdownNow();
        }
        asyncSinks.forEach(AsyncTickSink::close);
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
