package com.tradewind.common.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared Kafka client settings. Every blocking path has an explicit timeout; producers are idempotent and
 * batch; consumers commit manually after their DB transaction commits.
 */
public final class KafkaDefaults {

    public static final int PRODUCER_LINGER_MS = 5;
    public static final int PRODUCER_BATCH_SIZE_BYTES = 64 * 1024;
    public static final int PRODUCER_MAX_BLOCK_MS = 5_000;
    public static final int PRODUCER_REQUEST_TIMEOUT_MS = 5_000;
    public static final int PRODUCER_DELIVERY_TIMEOUT_MS = 30_000;
    public static final int CONSUMER_API_TIMEOUT_MS = 10_000;
    public static final int CONSUMER_REQUEST_TIMEOUT_MS = 10_000;
    public static final int CONSUMER_MAX_POLL_RECORDS = 500;

    private KafkaDefaults() {
    }

    public static Map<String, Object> producer(String bootstrapServers) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.LINGER_MS_CONFIG, PRODUCER_LINGER_MS);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, PRODUCER_BATCH_SIZE_BYTES);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, PRODUCER_MAX_BLOCK_MS);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, PRODUCER_REQUEST_TIMEOUT_MS);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, PRODUCER_DELIVERY_TIMEOUT_MS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return props;
    }

    public static Map<String, Object> consumer(String bootstrapServers, String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, CONSUMER_MAX_POLL_RECORDS);
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, CONSUMER_API_TIMEOUT_MS);
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, CONSUMER_REQUEST_TIMEOUT_MS);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return props;
    }
}
