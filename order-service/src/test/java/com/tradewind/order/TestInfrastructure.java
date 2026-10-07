package com.tradewind.order;

import com.tradewind.common.clock.MarketClock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/** Real Postgres, Redis and Kafka (as Spring beans), plus a market clock and portfolio stand-in the tests control. */
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

    @Bean
    KafkaContainer kafka() throws Exception {
        KafkaContainer kafka = new KafkaContainer("apache/kafka:4.3.1");
        kafka.start();
        com.tradewind.common.kafka.KafkaTopics.ensure(kafka.getBootstrapServers(),
                java.util.List.of(com.tradewind.common.kafka.Topics.ORDERS_SUBMITTED,
                        com.tradewind.common.kafka.Topics.EXECUTIONS), java.time.Duration.ofSeconds(30));
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

    @Bean
    @Primary
    FakeReservations fakeReservations() {
        return new FakeReservations();
    }
}
