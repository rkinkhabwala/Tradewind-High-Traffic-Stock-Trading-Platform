package com.tradewind.portfolio;

import com.tradewind.common.kafka.KafkaTopics;
import com.tradewind.common.kafka.Topics;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;

/** Real Postgres and Kafka for portfolio-service tests (same images as docker-compose). */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.15"));
    }

    @Bean
    KafkaContainer kafka() throws Exception {
        KafkaContainer kafka = new KafkaContainer("apache/kafka:4.3.1");
        kafka.start();
        KafkaTopics.ensure(kafka.getBootstrapServers(), List.of(Topics.EXECUTIONS, Topics.LEDGER_EVENTS),
                Duration.ofSeconds(30));
        return kafka;
    }

    @Bean
    DynamicPropertyRegistrar kafkaProperties(KafkaContainer kafka) {
        return registry -> registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
}
