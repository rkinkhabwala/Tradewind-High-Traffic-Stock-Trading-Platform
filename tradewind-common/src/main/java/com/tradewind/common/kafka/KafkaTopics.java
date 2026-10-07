package com.tradewind.common.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Creates topics a service owns (broker auto-creation is off). Safe to call repeatedly. */
public final class KafkaTopics {

    private KafkaTopics() {
    }

    /** Throws if the broker cannot be reached within {@code timeout}; callers retry later. */
    public static void ensure(String bootstrapServers, List<TopicSpec> topics, Duration timeout)
            throws ExecutionException, InterruptedException, TimeoutException {
        int timeoutMs = (int) timeout.toMillis();
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMs,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeoutMs))) {
            for (TopicSpec topic : topics) {
                try {
                    admin.createTopics(List.of(new NewTopic(topic.name(), topic.partitions(), (short) 1)))
                            .all().get(timeoutMs, TimeUnit.MILLISECONDS);
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) {
                        throw e;
                    }
                }
            }
        }
    }
}
