package com.tradewind.stream;

import com.tradewind.common.event.EventEnvelope;
import com.tradewind.common.event.EventJson;
import com.tradewind.common.event.EventTypes;
import com.tradewind.common.kafka.KafkaDefaults;
import com.tradewind.common.kafka.KafkaTopics;
import com.tradewind.common.kafka.Topics;
import com.tradewind.common.money.Price;
import com.tradewind.common.quote.QuoteTick;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Real Kafka and Redis (same images as docker-compose), the topic created up front, and a tick producer. */
@TestConfiguration(proxyBeanMethods = false)
public class TestInfrastructure {

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>("redis:7.4.11").withExposedPorts(6379);
    }

    @Bean
    KafkaContainer kafka() throws Exception {
        KafkaContainer kafka = new KafkaContainer("apache/kafka:4.3.1");
        kafka.start();
        KafkaTopics.ensure(kafka.getBootstrapServers(), List.of(Topics.QUOTES_TICKS), Duration.ofSeconds(30));
        return kafka;
    }

    @Bean
    DynamicPropertyRegistrar kafkaProperties(KafkaContainer kafka) {
        return registry -> registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Bean(destroyMethod = "close")
    Ticks testTicks(KafkaContainer kafka) {
        return new Ticks(new KafkaProducer<>(KafkaDefaults.producer(kafka.getBootstrapServers())));
    }

    /** Publishes QuoteTick events the way marketdata-service does. */
    public record Ticks(Producer<String, String> producer) implements AutoCloseable {

        public void send(String symbol, String last) {
            Price l = Price.of(last);
            QuoteTick tick = new QuoteTick(symbol, Price.of(l.value().subtract(new BigDecimal("0.01"))),
                    Price.of(l.value().add(new BigDecimal("0.01"))), l, 100, 100, 0, Instant.now());
            producer.send(new ProducerRecord<>(Topics.QUOTES_TICKS.name(), symbol, EventJson.write(
                    EventEnvelope.of(EventTypes.QUOTE_TICK, QuoteTick.VERSION, symbol, tick, Clock.systemUTC()))));
        }

        public void flush() {
            producer.flush();
        }

        @Override
        public void close() {
            producer.close(Duration.ofSeconds(5));
        }
    }
}
