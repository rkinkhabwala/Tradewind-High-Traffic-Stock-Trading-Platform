package com.tradewind.portfolio.messaging;

import com.tradewind.common.kafka.KafkaDefaults;
import com.tradewind.common.kafka.KafkaTopics;
import com.tradewind.common.kafka.Topics;
import com.tradewind.common.outbox.OutboxRelay;
import com.tradewind.common.outbox.OutboxWriter;
import com.tradewind.common.userevent.UserEventPublisher;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** portfolio-service owns the ledger.events topic and publishes to it only through the transactional outbox. */
@Configuration
class MessagingConfig {

    /** Advisory-lock key: one active outbox relay across all portfolio-service instances. */
    static final long OUTBOX_LOCK_KEY = 0x0B_00_0001L;

    @Bean(destroyMethod = "close")
    Producer<String, String> kafkaProducer(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        return new KafkaProducer<>(KafkaDefaults.producer(bootstrapServers));
    }

    /** Best-effort, after-commit pushes to Redis channel user:{accountId} for stream-service. */
    @Bean(destroyMethod = "close")
    UserEventPublisher userEventPublisher(StringRedisTemplate redis, MeterRegistry meters) {
        UserEventPublisher publisher = new UserEventPublisher(redis::convertAndSend);
        FunctionCounter.builder("user.events", publisher, UserEventPublisher::published).tag("outcome", "published").register(meters);
        FunctionCounter.builder("user.events", publisher, UserEventPublisher::dropped).tag("outcome", "dropped").register(meters);
        FunctionCounter.builder("user.events", publisher, UserEventPublisher::failed).tag("outcome", "failed").register(meters);
        return publisher;
    }

    @Bean
    OutboxWriter outboxWriter(JdbcClient jdbc) {
        return new OutboxWriter(jdbc);
    }

    @Bean
    OutboxRelay outboxRelay(JdbcClient jdbc, TransactionTemplate transactions, Producer<String, String> producer,
                            @Value("${tradewind.outbox.batch-size:500}") int batchSize, Clock clock) {
        return new OutboxRelay(jdbc, transactions, producer, OUTBOX_LOCK_KEY, batchSize, Duration.ofSeconds(10), clock);
    }

    @Bean
    OutboxRelayScheduler outboxRelayScheduler(OutboxRelay relay, Clock clock,
                                              @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                                              @Value("${tradewind.outbox.batch-size:500}") int batchSize) {
        return new OutboxRelayScheduler(relay, clock, bootstrapServers, batchSize);
    }

    static class OutboxRelayScheduler {
        private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

        private final OutboxRelay relay;
        private final Clock clock;
        private final String bootstrapServers;
        private final int batchSize;
        private volatile boolean topicsReady;
        @org.springframework.beans.factory.annotation.Value("${tradewind.outbox.retention:10m}")
        private Duration retention = Duration.ofMinutes(10);

        OutboxRelayScheduler(OutboxRelay relay, Clock clock, String bootstrapServers, int batchSize) {
            this.relay = relay;
            this.clock = clock;
            this.bootstrapServers = bootstrapServers;
            this.batchSize = batchSize;
        }

        @Scheduled(fixedDelayString = "${tradewind.outbox.poll-interval:50ms}")
        void relay() {
            if (!topicsReady && !ensureTopics()) {
                return;
            }
            try {
                for (int i = 0; i < 20 && relay.relayOnce() == batchSize; i++) {
                    log.debug("outbox backlog, relaying another batch");
                }
            } catch (RuntimeException e) {
                log.warn("Outbox relay failed: {}", e.toString());
            }
        }

        /** Published rows are kept briefly for debugging, then deleted (tradewind.outbox.retention, default 10 min). */
        @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
        void purge() {
            try {
                relay.purgePublishedBefore(clock.instant().minus(retention));
            } catch (RuntimeException e) {
                log.warn("Outbox purge failed, will retry: {}", e.toString());
            }
        }

        private boolean ensureTopics() {
            try {
                KafkaTopics.ensure(bootstrapServers, List.of(Topics.LEDGER_EVENTS), Duration.ofSeconds(5));
                topicsReady = true;
            } catch (Exception e) {
                log.warn("Kafka not ready, outbox relay waiting: {}", e.toString());
            }
            return topicsReady;
        }
    }
}
