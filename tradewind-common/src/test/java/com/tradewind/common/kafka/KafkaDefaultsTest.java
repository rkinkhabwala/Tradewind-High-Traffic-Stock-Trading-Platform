package com.tradewind.common.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaDefaultsTest {

    @Test
    void producerIsIdempotentDurableAndBounded() {
        Map<String, Object> p = KafkaDefaults.producer("localhost:9092");

        assertThat(p).containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .containsKey(ProducerConfig.MAX_BLOCK_MS_CONFIG)
                .containsKey(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG);
        // Kafka refuses to start a producer unless delivery.timeout.ms >= linger.ms + request.timeout.ms.
        assertThat(KafkaDefaults.PRODUCER_DELIVERY_TIMEOUT_MS)
                .isGreaterThanOrEqualTo(KafkaDefaults.PRODUCER_LINGER_MS + KafkaDefaults.PRODUCER_REQUEST_TIMEOUT_MS);
    }

    @Test
    void consumerCommitsManuallyAndHasTimeouts() {
        Map<String, Object> c = KafkaDefaults.consumer("localhost:9092", "portfolio-service");

        assertThat(c).containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "portfolio-service")
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .containsEntry(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
                .containsKey(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG)
                .containsKey(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG);
    }

    @Test
    void topicsMatchSpec() {
        assertThat(Topics.ALL).extracting(TopicSpec::name)
                .containsExactly("quotes.ticks", "orders.submitted", "executions", "ledger.events");
        assertThat(Topics.ALL).allSatisfy(t -> assertThat(t.partitions()).isEqualTo(12));
    }
}
