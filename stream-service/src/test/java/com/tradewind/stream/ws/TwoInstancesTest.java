package com.tradewind.stream.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradewind.common.quote.QuoteHash;
import com.tradewind.common.userevent.UserEventPublisher;
import com.tradewind.stream.StreamServiceApplication;
import com.tradewind.stream.TestInfrastructure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Two stream-service instances on the same Kafka and Redis, as with {@code --scale stream-service=2}: both fan out
 * the same quotes, and a user event reaches the account's sockets on whichever instances hold them, and only those.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "tradewind.stream.instance-id=instance-a")
@Import(TestInfrastructure.class)
class TwoInstancesTest {

    static ConfigurableApplicationContext instanceB;
    @SuppressWarnings("rawtypes")
    static final DefaultRedisScript<List> NUMSUB =
            new DefaultRedisScript<>("return redis.call('PUBSUB', 'NUMSUB', KEYS[1])", List.class);

    @LocalServerPort
    int portA;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    TestInfrastructure.Ticks ticks;
    @Autowired
    KafkaContainer kafka;
    @Autowired
    @Qualifier("redis")
    GenericContainer<?> redisContainer;
    @Autowired
    @Qualifier("ticks")
    HealthIndicator tickConsumerA;

    int portB;

    @BeforeEach
    void startSecondInstance() {
        if (instanceB == null) {
            instanceB = new SpringApplicationBuilder(StreamServiceApplication.class).run(
                    "--server.port=0",
                    "--tradewind.stream.instance-id=instance-b",
                    "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                    "--spring.data.redis.host=" + redisContainer.getHost(),
                    "--spring.data.redis.port=" + redisContainer.getMappedPort(6379));
        }
        portB = ((WebServerApplicationContext) instanceB).getWebServer().getPort();
        HealthIndicator tickConsumerB = instanceB.getBean("ticks", HealthIndicator.class);
        await().atMost(Duration.ofSeconds(30)).until(() -> tickConsumerA.health().getStatus().equals(Status.UP)
                && tickConsumerB.health().getStatus().equals(Status.UP));
        redis.opsForSet().add(QuoteHash.SYMBOLS_KEY, "MULTI");
    }

    @AfterAll
    static void stopSecondInstance() {
        if (instanceB != null) {
            instanceB.close();
            instanceB = null;
        }
    }

    private long subscribers(UUID account) {
        List<?> reply = redis.execute(NUMSUB, List.of(UserEventPublisher.channel(account)));
        return ((Number) reply.get(1)).longValue();
    }

    @Test
    void clientsOnDifferentInstancesReceiveTheSameQuotes() throws Exception {
        try (WsClient onA = WsClient.connect(portA); WsClient onB = WsClient.connect(portB)) {
            assertThat(onA.await("hello").path("instanceId").asText()).isEqualTo("instance-a");
            assertThat(onB.await("hello").path("instanceId").asText()).isEqualTo("instance-b");
            onA.send("{\"op\":\"subscribe\",\"symbols\":[\"MULTI\"]}");
            onB.send("{\"op\":\"subscribe\",\"symbols\":[\"MULTI\"]}");
            onA.await("subscriptions");
            onB.await("subscriptions");

            JsonNode a = null;
            JsonNode b = null;
            for (int i = 0; i < 50 && (a == null || b == null); i++) {
                ticks.send("MULTI", "77.77");
                ticks.flush();
                try {
                    a = a != null ? a : onA.await(WsClient.quoteFor("MULTI"), Duration.ofMillis(200));
                    b = b != null ? b : onB.await(WsClient.quoteFor("MULTI"), Duration.ofMillis(200));
                } catch (AssertionError retry) {
                    // not yet
                }
            }
            assertThat(a).isNotNull();
            assertThat(b).isNotNull();
            assertThat(a.path("last").asText()).isEqualTo("77.7700").isEqualTo(b.path("last").asText());
        }
    }

    @Test
    void userEventsReachEveryInstanceHoldingTheAccountAndNoOneElse() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        try (UserEventPublisher publisher = new UserEventPublisher(redis::convertAndSend);
             WsClient aliceOnA = new WsClient(portA, UUID.randomUUID(), alice);
             WsClient aliceOnB = new WsClient(portB, UUID.randomUUID(), alice);
             WsClient bobOnB = new WsClient(portB, UUID.randomUUID(), bob)) {
            await().atMost(Duration.ofSeconds(5)).until(() -> subscribers(alice) == 2 && subscribers(bob) == 1);

            publisher.publishAfterCommit(alice, Map.of("type", "order", "order", Map.of("status", "FILLED")));

            JsonNode onA = aliceOnA.await("order");
            JsonNode onB = aliceOnB.await("order");
            assertThat(onA.path("order").path("status").asText()).isEqualTo("FILLED");
            assertThat(onB).isEqualTo(onA);
            assertThat(bobOnB.collect(Duration.ofMillis(500))).noneMatch(m -> "order".equals(m.path("type").asText()));

            // Instance A drops the channel when Alice's last socket there closes; B keeps it.
            aliceOnA.close();
            await().atMost(Duration.ofSeconds(5)).until(() -> subscribers(alice) == 1);
            publisher.publishAfterCommit(alice, Map.of("type", "portfolio", "reason", "FILL"));
            assertThat(aliceOnB.await("portfolio").path("reason").asText()).isEqualTo("FILL");
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> subscribers(alice) == 0 && subscribers(bob) == 0);
    }
}
