package com.tradewind.stream.quote;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradewind.common.event.EventEnvelope;
import com.tradewind.common.event.EventJson;
import com.tradewind.common.event.EventTypes;
import com.tradewind.common.kafka.KafkaDefaults;
import com.tradewind.common.kafka.KafkaTopics;
import com.tradewind.common.kafka.Topics;
import com.tradewind.common.quote.QuoteTick;
import com.tradewind.stream.session.SessionRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Reads every partition of {@code quotes.ticks} from the latest offset. Partitions are assigned directly rather than
 * through a consumer group: each instance must see every tick (spec 6.2), there is nothing to commit, and a
 * restarted instance only cares about prices from now on (current prices come from Redis at subscribe time).
 */
@Component("ticks")
class TickConsumer implements SmartLifecycle, HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(TickConsumer.class);
    private static final Duration POLL = Duration.ofMillis(100);
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(1);

    private final SessionRegistry sessions;
    private final String bootstrapServers;
    private final Clock clock = Clock.systemUTC();
    private final Counter malformed;
    private final Counter received;
    private final Timer lag;
    private volatile boolean running;
    private volatile boolean assigned;
    private volatile KafkaConsumer<String, String> consumer;
    private Thread thread;

    TickConsumer(SessionRegistry sessions, @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                 MeterRegistry meters) {
        this.sessions = sessions;
        this.bootstrapServers = bootstrapServers;
        this.malformed = Counter.builder("stream.ticks.malformed").register(meters);
        this.received = Counter.builder("stream.ticks.received").register(meters);
        this.lag = Timer.builder("stream.ticks.lag").description("Tick publish to receipt (wall clock)")
                .publishPercentiles(0.5, 0.99).register(meters);
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofPlatform().name("tick-consumer").start(this::run);
    }

    @Override
    public void stop() {
        running = false;
        KafkaConsumer<String, String> c = consumer;
        if (c != null) {
            c.wakeup();
        }
        try {
            if (thread != null) {
                thread.join(Duration.ofSeconds(5));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public Health health() {
        return assigned ? Health.up().build() : Health.down().withDetail("reason", "not consuming quotes.ticks").build();
    }

    private void run() {
        while (running) {
            if (!KafkaTopics.ensureWithRetry(bootstrapServers, List.of(Topics.QUOTES_TICKS), () -> running)) {
                return;
            }
            try (KafkaConsumer<String, String> c = new KafkaConsumer<>(consumerProperties())) {
                consumer = c;
                List<TopicPartition> partitions = c.partitionsFor(Topics.QUOTES_TICKS.name()).stream()
                        .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
                c.assign(partitions);
                c.seekToEnd(partitions);
                partitions.forEach(c::position);       // resolve "end" now, not lazily on the first poll
                assigned = true;
                log.info("Consuming {} partitions of {} from the latest offset", partitions.size(), Topics.QUOTES_TICKS.name());
                while (running) {
                    for (ConsumerRecord<String, String> record : c.poll(POLL)) {
                        dispatch(record.value());
                    }
                }
            } catch (WakeupException e) {
                // stopping
            } catch (RuntimeException e) {
                log.warn("Tick consumer failed, restarting: {}", e.toString());
                sleep();
            } finally {
                assigned = false;
                consumer = null;
            }
        }
    }

    private void dispatch(String json) {
        try {
            EventEnvelope<JsonNode> raw = EventJson.readRaw(json);
            if (!EventTypes.QUOTE_TICK.equals(raw.eventType())) {
                return;
            }
            received.increment();
            lag.record(Duration.between(raw.occurredAt(), clock.instant()));
            if (!sessions.hasSubscribers(raw.key())) {
                return;
            }
            QuoteTick tick = EventJson.convert(raw.payload(), QuoteTick.class);
            sessions.publishQuote(tick.symbol(), QuoteMessages.live(tick, raw.occurredAt()), raw.occurredAt().toEpochMilli());
        } catch (RuntimeException e) {
            malformed.increment();
            log.debug("Skipping malformed tick: {}", e.toString());
        }
    }

    private Map<String, Object> consumerProperties() {
        Map<String, Object> props = KafkaDefaults.consumer(bootstrapServers, "unused");
        props.remove(ConsumerConfig.GROUP_ID_CONFIG);                 // direct assignment, no group, no commits
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_uncommitted");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 2000);
        return props;
    }

    private void sleep() {
        try {
            Thread.sleep(RETRY_BACKOFF);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
