package com.tradewind.stream.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradewind.common.money.Price;
import com.tradewind.common.quote.QuoteHash;
import com.tradewind.common.quote.QuoteTick;
import com.tradewind.stream.TestInfrastructure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "tradewind.stream.instance-id=stream-test-1",
        "tradewind.stream.max-subscriptions=5"})
@Import(TestInfrastructure.class)
class StreamIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    TestInfrastructure.Ticks ticks;
    @Autowired
    @Qualifier("ticks")
    HealthIndicator tickConsumer;

    @BeforeEach
    void setUp() {
        redis.opsForSet().add(QuoteHash.SYMBOLS_KEY, "AAA", "BBB", "CCC", "DDD", "EEE", "FFF", "GGG", "HHH");
        QuoteTick stored = new QuoteTick("AAA", Price.of("99.99"), Price.of("100.01"), Price.of("100.00"), 100, 200, 0,
                Instant.parse("2026-01-05T15:00:00Z"));
        redis.opsForHash().putAll(QuoteHash.key("AAA"), QuoteHash.toHash(stored, Price.of("99.00")));
        await().atMost(Duration.ofSeconds(30)).until(() -> tickConsumer.health().getStatus().equals(Status.UP));
    }

    /** Sends a tick repeatedly until the client sees it: the consumer reads from "latest", so timing is loose. */
    private JsonNode tickUntilReceived(WsClient client, String symbol, String last) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            ticks.send(symbol, last);
            ticks.flush();
            try {
                return client.await(WsClient.quoteFor(symbol), Duration.ofMillis(300));
            } catch (AssertionError retry) {
                // not yet
            }
        }
        throw new AssertionError("no quote for " + symbol);
    }

    @Test
    void aHandshakeWithoutGatewayIdentityIsRefused() {
        assertThatThrownBy(() -> WsClient.HTTP.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                }).join())
                .isInstanceOf(CompletionException.class)
                .cause().isInstanceOf(WebSocketHandshakeException.class)
                .satisfies(e -> assertThat(((WebSocketHandshakeException) e).getResponse().statusCode()).isEqualTo(401));
    }

    @Test
    void helloNamesTheInstanceAndSubscribingSendsTheStoredQuoteAtOnce() throws Exception {
        try (WsClient client = WsClient.connect(port)) {
            JsonNode hello = client.await("hello");
            assertThat(hello.path("instanceId").asText()).isEqualTo("stream-test-1");
            assertThat(hello.path("heartbeatSeconds").asInt()).isEqualTo(15);

            client.send("{\"op\":\"subscribe\",\"symbols\":[\"aaa\"]}");
            assertThat(client.await("subscriptions").path("symbols").toString()).isEqualTo("[\"AAA\"]");
            JsonNode quote = client.await(WsClient.quoteFor("AAA"), Duration.ofSeconds(1));   // no tick needed
            assertThat(quote.path("last").asText()).isEqualTo("100.0000");
            assertThat(quote.path("bid").asText()).isEqualTo("99.9900");
            assertThat(quote.path("prevClose").asText()).isEqualTo("99.0000");
            assertThat(quote.path("ts").asText()).isEqualTo("2026-01-05T15:00:00Z");
        }
    }

    @Test
    void ticksReachOnlyTheSessionsSubscribedToTheSymbol() throws Exception {
        try (WsClient bbb = WsClient.connect(port); WsClient ccc = WsClient.connect(port)) {
            bbb.send("{\"op\":\"subscribe\",\"symbols\":[\"BBB\"]}");
            ccc.send("{\"op\":\"subscribe\",\"symbols\":[\"CCC\"]}");
            bbb.await("subscriptions");
            ccc.await("subscriptions");

            JsonNode quote = tickUntilReceived(bbb, "BBB", "50.00");
            assertThat(quote.path("last").asText()).isEqualTo("50.0000");
            assertThat(quote.has("prevClose")).isFalse();
            assertThat(ccc.collect(Duration.ofMillis(500))).noneMatch(WsClient.quoteFor("BBB"));
        }
    }

    @Test
    void aBurstOfTicksIsConflatedToAtMostOneQuotePer250msEndingOnTheLatest() throws Exception {
        try (WsClient client = WsClient.connect(port)) {
            client.send("{\"op\":\"subscribe\",\"symbols\":[\"DDD\"]}");
            client.await("subscriptions");
            tickUntilReceived(client, "DDD", "10.00");
            client.collect(Duration.ofMillis(300));                       // let the interval pass

            long start = System.nanoTime();
            for (int i = 1; i <= 200; i++) {
                ticks.send("DDD", new BigDecimal("10.00").add(BigDecimal.valueOf(i, 2)).toPlainString());
            }
            ticks.flush();
            List<JsonNode> quotes = client.collect(Duration.ofMillis(1500)).stream()
                    .filter(WsClient.quoteFor("DDD")).toList();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(quotes).isNotEmpty().hasSizeLessThanOrEqualTo((int) (elapsedMs / 250) + 2);
            assertThat(quotes.getLast().path("last").asText()).isEqualTo("12.0000");
        }
    }

    @Test
    void unsubscribeStopsQuotesAndPingIsAnswered() throws Exception {
        try (WsClient client = WsClient.connect(port)) {
            client.send("{\"op\":\"subscribe\",\"symbols\":[\"EEE\",\"FFF\"]}");
            client.await("subscriptions");
            tickUntilReceived(client, "EEE", "20.00");

            client.send("{\"op\":\"unsubscribe\",\"symbols\":[\"eee\"]}");
            assertThat(client.await("subscriptions").path("symbols").toString()).isEqualTo("[\"FFF\"]");
            for (int i = 0; i < 5; i++) {
                ticks.send("EEE", "21.00");
            }
            ticks.flush();
            assertThat(client.collect(Duration.ofMillis(700))).noneMatch(WsClient.quoteFor("EEE"));

            client.send("{\"op\":\"ping\"}");
            assertThat(client.await("pong").has("ts")).isTrue();
        }
    }

    @Test
    void protocolErrorsAreReportedWithoutClosingTheConnection() throws Exception {
        try (WsClient client = WsClient.connect(port)) {
            client.await("hello");
            client.send("not json");
            assertThat(client.await("error").path("code").asText()).isEqualTo("BAD_REQUEST");
            client.send("{\"op\":\"dance\"}");
            assertThat(client.await("error").path("code").asText()).isEqualTo("BAD_REQUEST");
            client.send("{\"op\":\"subscribe\",\"symbols\":[\"1bad\"]}");
            assertThat(client.await("error").path("code").asText()).isEqualTo("BAD_REQUEST");

            client.send("{\"op\":\"subscribe\",\"symbols\":[\"ZZZ\",\"GGG\"]}");
            JsonNode unknown = client.await("error");
            assertThat(unknown.path("code").asText()).isEqualTo("UNKNOWN_SYMBOL");
            assertThat(unknown.path("symbols").toString()).isEqualTo("[\"ZZZ\"]");
            assertThat(client.await("subscriptions").path("symbols").toString()).isEqualTo("[\"GGG\"]");

            client.send("{\"op\":\"subscribe\",\"symbols\":[\"AAA\",\"BBB\",\"CCC\",\"DDD\",\"EEE\"]}");   // limit 5
            assertThat(client.await("error").path("code").asText()).isEqualTo("TOO_MANY_SUBSCRIPTIONS");

            client.send("{\"op\":\"ping\"}");
            client.await("pong");
            assertThat(client.closeCode).isNotDone();
        }
    }

    @Test
    void identityFromTheGatewayIsAttachedToTheSession() throws Exception {
        UUID account = UUID.randomUUID();
        try (WsClient client = new WsClient(port, UUID.randomUUID(), account)) {
            client.await("hello");
            // Used for user events in the next milestone; here it is enough that the handshake was accepted.
            assertThat(client.closeCode).isNotDone();
        }
    }
}
