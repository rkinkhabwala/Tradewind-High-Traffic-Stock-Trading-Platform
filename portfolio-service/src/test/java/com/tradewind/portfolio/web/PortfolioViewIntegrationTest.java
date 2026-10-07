package com.tradewind.portfolio.web;

import com.tradewind.common.event.EventEnvelope;
import com.tradewind.common.event.EventJson;
import com.tradewind.common.event.EventTypes;
import com.tradewind.common.execution.ExecutionReported;
import com.tradewind.common.kafka.KafkaDefaults;
import com.tradewind.common.kafka.Topics;
import com.tradewind.common.money.Price;
import com.tradewind.common.money.Quantity;
import com.tradewind.common.order.OrderSide;
import com.tradewind.common.quote.QuoteHash;
import com.tradewind.common.quote.QuoteTick;
import com.tradewind.portfolio.PostgresTestConfiguration;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class PortfolioViewIntegrationTest {

    @Autowired
    TestRestTemplate http;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    KafkaContainer kafka;

    private final UUID account = UUID.randomUUID();

    private HttpEntity<Void> asAccount() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Account-Id", account.toString());
        return new HttpEntity<>(headers);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        return http.exchange(path, HttpMethod.GET, asAccount(), Map.class).getBody();
    }

    private void buy(String symbol, String qty, String price, long reserveCents) throws Exception {
        UUID order = UUID.randomUUID();
        http.postForEntity("/internal/reservations", Map.of("orderId", order, "accountId", account, "cashCents", reserveCents), Map.class);
        ExecutionReported fill = new ExecutionReported(UUID.randomUUID(), order, account, symbol, OrderSide.BUY, 1,
                Quantity.of(qty), Price.of(price), Quantity.of(qty), Quantity.ZERO, true,
                Instant.parse("2026-01-05T15:00:00Z"));   // simulated market time, earlier than any real timestamp
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(KafkaDefaults.producer(kafka.getBootstrapServers()))) {
            producer.send(new ProducerRecord<>(Topics.EXECUTIONS.name(), account.toString(), EventJson.write(
                    EventEnvelope.of(EventTypes.EXECUTION_REPORTED, 1, account.toString(), fill, Clock.systemUTC())))).get();
        }
    }

    private void quote(String symbol, String last, String prevClose) {
        Price l = Price.of(last);
        QuoteTick tick = new QuoteTick(symbol, Price.of(l.value().subtract(new java.math.BigDecimal("0.01"))),
                Price.of(l.value().add(new java.math.BigDecimal("0.01"))), l, 100, 100, 0, Instant.now());
        redis.opsForHash().putAll(QuoteHash.key(symbol), QuoteHash.toHash(tick, Price.of(prevClose)));
    }

    @Test
    void portfolioValuesPositionsAtTheLatestQuote() throws Exception {
        http.postForEntity("/internal/ledger/deposits",
                Map.of("referenceId", UUID.randomUUID(), "accountId", account, "amountCents", 10_000_000), Map.class);
        buy("AAPL", "10", "200.00", 210_000);
        buy("TSLA", "2.5", "240.00", 63_000);
        quote("AAPL", "210.00", "205.00");
        quote("TSLA", "236.00", "240.00");
        await().atMost(Duration.ofSeconds(20)).until(() -> ((List<?>) get("/api/portfolio").get("positions")).size() == 2);

        Map<String, Object> p = get("/api/portfolio");

        // cash 100,000 - 2,000 - 600 = 97,400.00; AAPL 10 x 210 = 2,100.00; TSLA 2.5 x 236 = 590.00
        assertThat(p).containsEntry("cashCents", 9_740_000).containsEntry("buyingPowerCents", 9_740_000)
                .containsEntry("cashReservedCents", 0).containsEntry("positionsValueCents", 269_000)
                .containsEntry("totalValueCents", 9_740_000 + 269_000).containsEntry("livePrices", true);
        // day change: 10 x (210 - 205) + 2.5 x (236 - 240) = 50 - 10 = 40.00
        assertThat(p).containsEntry("dayChangeCents", 4_000);
        Map<?, ?> aapl = ((List<?>) p.get("positions")).stream().map(x -> (Map<?, ?>) x)
                .filter(x -> x.get("symbol").equals("AAPL")).findFirst().orElseThrow();
        assertThat(aapl.get("avgCost")).isEqualTo("200.0000");
        assertThat(aapl.get("marketValueCents")).isEqualTo(210_000);
        assertThat(aapl.get("unrealizedPnlCents")).isEqualTo(10_000);
    }

    @Test
    void activityListsDepositsAndFillsNewestFirstWithCursorPaging() throws Exception {
        http.postForEntity("/internal/ledger/deposits",
                Map.of("referenceId", UUID.randomUUID(), "accountId", account, "amountCents", 1_000_000), Map.class);
        buy("GME", "4", "25.25", 10_605);
        buy("GME", "1", "25.50", 2_678);
        await().atMost(Duration.ofSeconds(20)).until(() -> ((List<?>) get("/api/activity").get("items")).size() == 3);

        List<Map<String, Object>> seen = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> page = get("/api/activity?limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
            for (Object item : (List<?>) page.get("items")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) item;
                seen.add(entry);
            }
            cursor = (String) page.get("nextCursor");
        } while (cursor != null);

        assertThat(seen.stream().map(a -> a.get("type")).toList()).containsExactly("FILL", "FILL", "DEPOSIT");
        assertThat(seen.getFirst()).containsEntry("symbol", "GME").containsEntry("side", "BUY")
                .containsEntry("qty", "1.000000").containsEntry("amountCents", -2_550);
        assertThat(seen.getLast()).containsEntry("amountCents", 1_000_000);
    }

    @Test
    void requestsWithoutGatewayIdentityAreRejected() {
        assertThat(http.getForEntity("/api/portfolio", Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.getForEntity("/api/activity", Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
