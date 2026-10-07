package com.tradewind.marketdata.quote;

import com.tradewind.common.kafka.KafkaDefaults;
import com.tradewind.common.kafka.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@Configuration
class KafkaConfig {

    @Bean
    ProducerFactory<String, String> producerFactory(KafkaConnectionDetails connection) {
        String bootstrapServers = String.join(",", connection.getProducer().getBootstrapServers());
        return new DefaultKafkaProducerFactory<>(KafkaDefaults.producer(bootstrapServers));
    }

    @Bean
    KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /** marketdata-service owns quotes.ticks. Ticks are only useful for minutes, so retention is short. */
    @Bean
    NewTopic quotesTicksTopic() {
        return TopicBuilder.name(Topics.QUOTES_TICKS.name())
                .partitions(Topics.QUOTES_TICKS.partitions())
                .replicas(1)
                .configs(Topics.configFor(Topics.QUOTES_TICKS))
                .build();
    }
}
