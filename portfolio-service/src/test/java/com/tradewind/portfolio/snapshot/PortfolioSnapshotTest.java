package com.tradewind.portfolio.snapshot;

import com.tradewind.common.clock.MarketClock;
import com.tradewind.common.execution.ExecutionReported;
import com.tradewind.common.money.Money;
import com.tradewind.common.money.Price;
import com.tradewind.common.money.Quantity;
import com.tradewind.common.order.OrderSide;
import com.tradewind.common.quote.QuoteHash;
import com.tradewind.common.quote.QuoteTick;
import com.tradewind.portfolio.PostgresTestConfiguration;
import com.tradewind.portfolio.fill.FillService;
import com.tradewind.portfolio.funding.FundingService;
import com.tradewind.portfolio.ledger.LedgerAccountKey;
import com.tradewind.portfolio.ledger.LedgerAmounts;
import com.tradewind.portfolio.reservation.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, PortfolioSnapshotTest.ClockOverride.class})
class PortfolioSnapshotTest {

    static final Instant MONDAY_PRE_OPEN = Instant.parse("2026-01-05T14:00:00Z");   // 09:00 New York

    static class TestClock implements MarketClock {
        final AtomicReference<Instant> now = new AtomicReference<>(MONDAY_PRE_OPEN);

        @Override
        public Instant now() {
            return now.get();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockOverride {
        @Bean
        @Primary
        TestClock testMarketClock() {
            return new TestClock();
        }
    }

    @Autowired
    TestClock clock;
    @Autowired
    PortfolioSnapshotJob job;
    @Autowired
    FundingService funding;
    @Autowired
    ReservationService reservations;
    @Autowired
    FillService fills;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestRestTemplate http;

    private void quote(String symbol, String last) {
        Price l = Price.of(last);
        QuoteTick tick = new QuoteTick(symbol, Price.of(l.value().subtract(new BigDecimal("0.01"))),
                Price.of(l.value().add(new BigDecimal("0.01"))), l, 100, 100, 0, Instant.now());
        redis.opsForHash().putAll(QuoteHash.key(symbol), QuoteHash.toHash(tick, l));
    }

    /** Deposit $100,000 and buy 10 shares at $200.00: cash $98,000.00 plus a 10-share position. */
    private UUID accountHolding(String symbol) {
        UUID account = UUID.randomUUID();
        funding.deposit(UUID.randomUUID(), account, Money.ofCents(10_000_000));
        UUID order = UUID.randomUUID();
        reservations.reserve(order, account, LedgerAccountKey.USD, LedgerAmounts.of(Money.ofCents(200_000)));
        fills.onExecution(UUID.randomUUID(), new ExecutionReported(UUID.randomUUID(), order, account, symbol,
                OrderSide.BUY, 1, Quantity.of("10"), Price.of("200.00"), Quantity.of("10"), Quantity.ZERO, true,
                Instant.now()));
        return account;
    }

    private List<Map<?, ?>> history(UUID account, String range) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Account-Id", account.toString());
        var response = http.exchange("/api/portfolio/history?range=" + range, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> points = new ArrayList<>();
        ((List<?>) response.getBody().get("points")).forEach(p -> points.add((Map<?, ?>) p));
        return points;
    }

    private long rows(UUID account) {
        return jdbc.queryForObject("SELECT count(*) FROM portfolio_snapshots WHERE account_id = ?", Long.class, account);
    }

    @Test
    void snapshotsEveryFiveSimulatedMinutesOnceAtTheCloseAndNeverOnWeekends() {
        clock.now.set(MONDAY_PRE_OPEN);
        UUID account = accountHolding("SNAP");
        quote("SNAP", "210.00");

        clock.now.set(Instant.parse("2026-01-05T15:02:30Z"));     // 10:02:30 -> bucket 10:00
        job.tick();
        clock.now.set(Instant.parse("2026-01-05T15:04:59Z"));     // same bucket: nothing new
        job.tick();
        quote("SNAP", "205.00");
        clock.now.set(Instant.parse("2026-01-05T15:07:00Z"));     // bucket 10:05
        job.tick();
        clock.now.set(Instant.parse("2026-01-05T21:30:00Z"));     // 16:30, after the close -> 16:00 snapshot
        job.tick();
        clock.now.set(Instant.parse("2026-01-10T16:00:00Z"));     // Saturday: nothing
        job.tick();

        assertThat(rows(account)).isEqualTo(3);
        List<Map<?, ?>> day = history(account, "1D");
        assertThat(day.stream().map(p -> (String) p.get("ts")).toList()).containsExactly(
                "2026-01-05T15:00:00Z", "2026-01-05T15:05:00Z", "2026-01-05T21:00:00Z");
        // cash 9,800,000 cents + 10 x last
        assertThat(day).extracting(p -> ((Number) p.get("valueCents")).longValue())
                .containsExactly(9_800_000L + 210_000, 9_800_000L + 205_000, 9_800_000L + 205_000);
    }

    @Test
    void anAccountHoldingAnUnpricedSymbolIsSkippedNotMisvalued() {
        UUID account = accountHolding("NOQUOTE");
        redis.delete(QuoteHash.key("NOQUOTE"));

        job.snapshotAll(Instant.parse("2026-02-02T15:00:00Z"));

        assertThat(rows(account)).isZero();
    }

    @Test
    void longRangesAreDownsampledToTheLastValuePerBin() {
        UUID account = UUID.randomUUID();
        Instant start = Instant.parse("2026-03-01T00:00:00Z");
        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < 10 * 288; i++) {                      // 10 days, every 5 minutes
            batch.add(new Object[]{account, Timestamp.from(start.plus(Duration.ofMinutes(5L * i))), 1_000_000L + i});
        }
        jdbc.batchUpdate("INSERT INTO portfolio_snapshots (account_id, ts, value_cents) VALUES (?, ?, ?)", batch);
        clock.now.set(start.plus(Duration.ofDays(10)));

        List<Map<?, ?>> week = history(account, "1W");
        List<Map<?, ?>> year = history(account, "1Y");

        assertThat(week).hasSizeBetween(7 * 48 - 1, 7 * 48 + 1);              // one point per 30 minutes
        assertThat(year).hasSize(10);                                       // one point per day
        // Each bin reports its last value: the final daily point is the very last snapshot.
        assertThat(((Number) year.getLast().get("valueCents")).longValue()).isEqualTo(1_000_000L + 10 * 288 - 1);
        assertThat(((Number) week.get(1).get("valueCents")).longValue()
                - ((Number) week.get(0).get("valueCents")).longValue()).isEqualTo(6);   // 6 snapshots per 30 min
    }

    @Test
    void historyNeedsIdentityAndAValidRange() {
        assertThat(http.getForEntity("/api/portfolio/history", Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Account-Id", UUID.randomUUID().toString());
        assertThat(http.exchange("/api/portfolio/history?range=2Y", HttpMethod.GET, new HttpEntity<>(headers), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.exchange("/api/portfolio/history?range=1D", HttpMethod.GET, new HttpEntity<>(headers), Map.class)
                .getBody().get("points")).isEqualTo(List.of());
    }
}
