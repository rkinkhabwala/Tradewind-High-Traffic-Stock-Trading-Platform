package com.tradewind.execution;

import com.tradewind.common.clock.MarketClock;
import com.tradewind.common.kafka.KafkaTopics;
import com.tradewind.common.kafka.Topics;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@TestConfiguration(proxyBeanMethods = false)
public class TestInfrastructure {

    public static final Instant MONDAY_10AM = Instant.parse("2026-01-05T15:00:00Z");

    public static class TestClock implements MarketClock {
        public final AtomicReference<Instant> now = new AtomicReference<>(MONDAY_10AM);

        @Override
        public Instant now() {
            return now.get();
        }
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16.15");
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>("redis:7.4.11").withExposedPorts(6379);
    }

    /** Topics exist before the consumer starts, as they do in a running system. */
    @Bean
    KafkaContainer kafka() throws Exception {
        KafkaContainer kafka = new KafkaContainer("apache/kafka:4.3.1");
        kafka.start();
        KafkaTopics.ensure(kafka.getBootstrapServers(),
                java.util.List.of(Topics.ORDERS_SUBMITTED, Topics.QUOTES_TICKS, Topics.EXECUTIONS), Duration.ofSeconds(30));
        return kafka;
    }

    @Bean
    DynamicPropertyRegistrar kafkaProperties(KafkaContainer kafka) {
        return registry -> registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Bean
    @Primary
    TestClock testMarketClock() {
        return new TestClock();
    }
}
