package com.tradewind.marketdata.quote;

import com.tradewind.common.event.EventEnvelope;
import com.tradewind.common.event.EventJson;
import com.tradewind.common.event.EventTypes;
import com.tradewind.common.kafka.Topics;
import com.tradewind.common.quote.QuoteTick;
import com.tradewind.marketdata.simulator.SimulatedTick;
import com.tradewind.marketdata.simulator.TickSink;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Publishes {@code QuoteTick} events to {@code quotes.ticks}, keyed by symbol so one partition carries a symbol's
 * ticks in order. Ticks are ephemeral market data, not state changes, so they are sent directly rather than via an
 * outbox; a failed send is counted and superseded by the next tick.
 */
@Component
public class KafkaTickPublisher implements TickSink {

    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final Counter failures;

    KafkaTickPublisher(KafkaTemplate<String, String> kafka, Clock clock, MeterRegistry meters) {
        this.kafka = kafka;
        this.clock = clock;
        this.failures = Counter.builder("marketdata.ticks.publish.failures").register(meters);
    }

    @Override
    public void accept(List<SimulatedTick> batch) {
        for (SimulatedTick t : batch) {
            QuoteTick tick = t.tick();
            String json = EventJson.write(EventEnvelope.of(EventTypes.QUOTE_TICK, QuoteTick.VERSION, tick.symbol(), tick, clock));
            kafka.send(Topics.QUOTES_TICKS.name(), tick.symbol(), json)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            failures.increment();
                        }
                    });
        }
    }
}
