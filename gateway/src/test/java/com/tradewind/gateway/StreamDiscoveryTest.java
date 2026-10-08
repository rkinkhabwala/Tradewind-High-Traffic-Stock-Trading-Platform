package com.tradewind.gateway;

import com.tradewind.gateway.stream.InstanceResolver;
import com.tradewind.gateway.stream.StreamInstances;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * /ws routing over several stream-service instances: round-robin across the healthy ones, and an instance that
 * fails its health check, disappears from DNS or dies drops out within one refresh (300ms here, 2s by default).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(StreamDiscoveryTest.Resolver.class)
class StreamDiscoveryTest {

    static final StubDownstream ACCOUNT = start(() -> new StubDownstream(GatewayRoutingSecurityTest.SIGNING_KEY));
    static final StubStreamInstance A = start(() -> new StubStreamInstance("a"));
    static final StubStreamInstance B = start(() -> new StubStreamInstance("b"));
    static final StubStreamInstance C = start(() -> new StubStreamInstance("c"));
    static final AtomicReference<List<URI>> DNS = new AtomicReference<>(List.of());

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.11").withExposedPorts(6379);

    /** Stands in for Docker's DNS answer for "stream-service". */
    @TestConfiguration(proxyBeanMethods = false)
    static class Resolver {
        @Bean
        @Primary
        InstanceResolver testResolver() {
            return DNS::get;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ACCOUNT_SERVICE_URL", ACCOUNT::baseUrl);
        registry.add("tradewind.stream.discovery.refresh-interval", () -> "300ms");
        registry.add("tradewind.stream.discovery.health-timeout", () -> "200ms");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @AfterAll
    static void stop() {
        ACCOUNT.stop();
        A.stop();
        B.stop();
        C.stop();
    }

    @Autowired
    WebTestClient web;
    @Autowired
    StreamInstances instances;

    @BeforeEach
    void allHealthy() {
        A.healthy = true;
        B.healthy = true;
        C.healthy = true;
        DNS.set(List.of(A.uri(), B.uri(), C.uri()));
        await().atMost(Duration.ofSeconds(5)).until(() -> instances.healthy().size() == 3);
    }

    /** Opens /ws the way a client does (fresh one-time ticket) and returns the instance that answered, or the status. */
    private String connect() {
        String user = UUID.randomUUID().toString();
        Map<?, ?> ticket = web.post().uri("/api/ws/ticket")
                .headers(h -> h.setBearerAuth(GatewayRoutingSecurityTest.validToken(user, UUID.randomUUID().toString())))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        var result = web.get().uri("/ws?ticket=" + ticket.get("ticket")).exchange().returnResult(Map.class);
        if (!result.getStatus().is2xxSuccessful()) {
            return String.valueOf(result.getStatus().value());
        }
        return (String) result.getResponseBody().blockFirst().get("instance");
    }

    private List<String> connect(int times) {
        List<String> answered = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            answered.add(connect());
        }
        return answered;
    }

    @Test
    void connectionsAreSpreadRoundRobinOverTheHealthyInstances() {
        List<String> answered = connect(9);
        assertThat(answered).containsOnly("a", "b", "c");
        for (String instance : List.of("a", "b", "c")) {
            assertThat(answered).filteredOn(instance::equals).hasSize(3);
        }
    }

    @Test
    void anInstanceFailingItsHealthCheckGetsNoNewClients() {
        B.healthy = false;
        await().atMost(Duration.ofSeconds(2)).until(() -> instances.healthy().size() == 2);
        assertThat(connect(6)).containsOnly("a", "c");

        B.healthy = true;           // recovered: back in rotation
        await().atMost(Duration.ofSeconds(2)).until(() -> instances.healthy().size() == 3);
        assertThat(connect(6)).contains("b");
    }

    @Test
    void anInstanceLeavingDnsOrDyingIsDroppedWithinARefresh() {
        DNS.set(List.of(A.uri(), C.uri()));                          // scaled down / container removed
        await().atMost(Duration.ofSeconds(2)).until(() -> instances.healthy().size() == 2);
        assertThat(connect(4)).containsOnly("a", "c");

        DNS.set(List.of(A.uri(), C.uri(), URI.create("http://127.0.0.1:1")));   // listed but nothing listening
        await().during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(2)).until(() -> instances.healthy().size() == 2);
        assertThat(connect(4)).containsOnly("a", "c");
    }

    @Test
    void withNoHealthyInstanceTheGatewayAnswers503() {
        DNS.set(List.of());
        await().atMost(Duration.ofSeconds(2)).until(() -> instances.healthy().isEmpty());
        assertThat(connect()).isEqualTo("503");
    }

    private interface Starter<T> {
        T start() throws Exception;
    }

    private static <T> T start(Starter<T> starter) {
        try {
            return starter.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
