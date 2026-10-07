package com.tradewind.order.portfolio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tradewind.common.money.Quantity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real HTTP against a stub portfolio-service whose behaviour each test chooses. */
class RestReservationClientTest {

    record Reply(int status, String body, long delayMillis) {
    }

    private HttpServer server;
    private final AtomicReference<Reply> reply = new AtomicReference<>(new Reply(200, "{}", 0));
    private final AtomicInteger requests = new AtomicInteger();
    private RestReservationClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            Reply r = reply.get();
            try {
                Thread.sleep(r.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(r.status(), bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        PortfolioClientProperties properties = new PortfolioClientProperties(
                "http://localhost:" + server.getAddress().getPort(), Duration.ofMillis(500), Duration.ofMillis(300),
                4, 50, Duration.ofMillis(250), Duration.ofSeconds(30));
        client = new RestReservationClient(RestClient.builder(), properties, new ObjectMapper(), new SimpleMeterRegistry());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void mapsBusinessOutcomes() {
        assertThat(client.reserveCash(UUID.randomUUID(), UUID.randomUUID(), 100)).isInstanceOf(ReservationClient.Reserved.class);

        reply.set(new Reply(422, "{\"code\":\"INSUFFICIENT_SHARES\"}", 0));
        assertThat(client.reserveShares(UUID.randomUUID(), UUID.randomUUID(), "AAPL", Quantity.of("1")))
                .isEqualTo(new ReservationClient.Insufficient("INSUFFICIENT_SHARES"));

        reply.set(new Reply(409, "{}", 0));
        assertThat(client.reserveCash(UUID.randomUUID(), UUID.randomUUID(), 100))
                .isInstanceOf(ReservationClient.AlreadyReleased.class);
    }

    @Test
    void timeoutsAndServerErrorsAreUnavailable() {
        reply.set(new Reply(200, "{}", 1_000));
        assertThatThrownBy(() -> client.reserveCash(UUID.randomUUID(), UUID.randomUUID(), 100))
                .isInstanceOf(ReservationClient.ReservationUnavailableException.class);

        reply.set(new Reply(503, "{}", 0));
        assertThatThrownBy(() -> client.release(UUID.randomUUID()))
                .isInstanceOf(ReservationClient.ReservationUnavailableException.class);
    }

    @Test
    void circuitOpensAfterRepeatedFailuresAndThenFailsFastWithoutCallingPortfolio() {
        reply.set(new Reply(500, "{}", 0));
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> client.reserveCash(UUID.randomUUID(), UUID.randomUUID(), 100))
                    .isInstanceOf(ReservationClient.ReservationUnavailableException.class);
        }
        int before = requests.get();

        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> client.reserveCash(UUID.randomUUID(), UUID.randomUUID(), 100))
                    .isInstanceOf(ReservationClient.ReservationUnavailableException.class)
                    .hasMessageContaining("circuit open");
        }
        assertThat(requests.get()).isEqualTo(before);
    }

    @Test
    void insufficientFundsDoNotTripTheBreaker() {
        reply.set(new Reply(422, "{\"code\":\"INSUFFICIENT_BUYING_POWER\"}", 0));
        for (int i = 0; i < 30; i++) {
            client.reserveCash(UUID.randomUUID(), UUID.randomUUID(), 100);
        }
        assertThat(client.circuitBreaker().getState().name()).isEqualTo("CLOSED");
    }

    @Test
    void bulkheadRejectsCallsBeyondTheConcurrencyLimitImmediately() throws Exception {
        reply.set(new Reply(200, "{}", 200));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(12)) {
            for (int i = 0; i < 12; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        client.reserveCash(UUID.randomUUID(), UUID.randomUUID(), 100);
                        return "ok";
                    } catch (ReservationClient.ReservationUnavailableException e) {
                        return e.getMessage();
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : results) {
                outcomes.add(f.get());
            }
            assertThat(outcomes).filteredOn("ok"::equals).hasSizeLessThanOrEqualTo(4);
            assertThat(outcomes).filteredOn(o -> o.contains("concurrent")).isNotEmpty();
        }
    }
}
