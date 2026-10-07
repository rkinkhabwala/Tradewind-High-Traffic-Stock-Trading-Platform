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

/**
 * Creates topics (broker auto-creation is off). Safe to call repeatedly, by any service: consumers call it before
 * subscribing, because a consumer that subscribes before its topic exists may not notice the topic for minutes.
 */
public final class KafkaTopics {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KafkaTopics.class);

    private KafkaTopics() {
    }

    /** Retries {@link #ensure} until it succeeds or {@code keepTrying} turns false. Returns whether it succeeded. */
    public static boolean ensureWithRetry(String bootstrapServers, List<TopicSpec> topics,
                                          java.util.function.BooleanSupplier keepTrying) {
        while (keepTrying.getAsBoolean()) {
            try {
                ensure(bootstrapServers, topics, Duration.ofSeconds(5));
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.warn("Kafka not ready ({}), retrying topic setup", e.toString());
                try {
                    Thread.sleep(Duration.ofSeconds(3));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
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
                    admin.createTopics(List.of(new NewTopic(topic.name(), topic.partitions(), (short) 1)
                                    .configs(Topics.configFor(topic))))
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
