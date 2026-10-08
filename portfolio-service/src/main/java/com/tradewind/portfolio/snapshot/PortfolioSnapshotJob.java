package com.tradewind.portfolio.snapshot;

import com.tradewind.common.clock.MarketClock;
import com.tradewind.common.clock.MarketHours;
import com.tradewind.common.money.Money;
import com.tradewind.common.quote.QuoteHash.StoredQuote;
import com.tradewind.portfolio.ledger.LedgerAmounts;
import com.tradewind.portfolio.quote.QuoteReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Writes {@code portfolio_snapshots} every 5 simulated minutes while the market is open, plus one closing snapshot
 * at 16:00, for every account with a non-zero cash, reserved or position balance. Value = cash + reserved cash +
 * sum(qty x last), positions priced from Redis in one pipelined read per batch. An account holding a symbol without
 * a quote is skipped for that bucket rather than recorded at a wrong value. Inserts ignore duplicates, so restarts
 * and several portfolio-service instances never double-write.
 */
@Component
public class PortfolioSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(PortfolioSnapshotJob.class);
    public static final Duration INTERVAL = Duration.ofMinutes(5);
    static final int BATCH = 500;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final QuoteReader quotes;
    private final MarketClock clock;
    private Instant lastBucket;

    PortfolioSnapshotJob(JdbcClient jdbc, JdbcTemplate jdbcTemplate, QuoteReader quotes, MarketClock clock) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.quotes = quotes;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tradewind.snapshots.check-interval:2s}")
    public synchronized void tick() {
        try {
            Instant bucket = dueBucket();
            if (bucket != null && !bucket.equals(lastBucket)) {
                int written = snapshotAll(bucket);
                lastBucket = bucket;
                log.debug("Portfolio snapshot {}: {} accounts", bucket, written);
            }
        } catch (RuntimeException e) {
            log.warn("Portfolio snapshot failed, will retry: {}", e.toString());
        }
    }

    /** The bucket to write now: the current 5-minute bucket while open, the 16:00 close after it, else none. */
    Instant dueBucket() {
        ZonedDateTime marketTime = clock.marketTime().withZoneSameInstant(MarketHours.ZONE);
        LocalDate session = marketTime.toLocalDate();
        if (clock.isMarketOpen()) {
            long seconds = INTERVAL.toSeconds();
            return Instant.ofEpochSecond(Math.floorDiv(marketTime.toEpochSecond(), seconds) * seconds);
        }
        Instant close = MarketHours.closeOf(session).toInstant();
        boolean afterClose = MarketHours.isTradingDay(session) && !marketTime.toInstant().isBefore(close);
        return afterClose ? close : null;
    }

    /** Snapshots every account holding anything, in keyset-paginated batches. Returns how many were written. */
    public int snapshotAll(Instant bucket) {
        int written = 0;
        UUID after = new UUID(0L, 0L);
        while (true) {
            List<UUID> batch = jdbc.sql("""
                            SELECT DISTINCT a.account_id
                            FROM ledger_accounts a JOIN balances b ON b.ledger_account_id = a.id
                            WHERE a.type <> 'HOUSE' AND b.amount <> 0 AND a.account_id > :after
                            ORDER BY a.account_id LIMIT :limit""")
                    .param("after", after).param("limit", BATCH)
                    .query(UUID.class).list();
            if (batch.isEmpty()) {
                return written;
            }
            written += snapshotBatch(batch, bucket);
            after = batch.getLast();
        }
    }

    private int snapshotBatch(List<UUID> accounts, Instant bucket) {
        Map<UUID, Long> cash = new HashMap<>();
        jdbc.sql("""
                        SELECT a.account_id, b.amount
                        FROM ledger_accounts a JOIN balances b ON b.ledger_account_id = a.id
                        WHERE a.account_id IN (:ids) AND a.type IN ('CASH', 'CASH_RESERVED')""")
                .param("ids", accounts)
                .query((rs, n) -> {
                    cash.merge(rs.getObject(1, UUID.class), LedgerAmounts.toMoney(rs.getBigDecimal(2)).cents(), Long::sum);
                    return null;
                }).list();
        record Holding(UUID account, String symbol, BigDecimal qty) {
        }
        List<Holding> holdings = jdbc.sql("SELECT account_id, symbol, qty FROM positions_view WHERE account_id IN (:ids)")
                .param("ids", accounts)
                .query((rs, n) -> new Holding(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3)))
                .list();
        Set<String> symbols = new HashSet<>();
        holdings.forEach(h -> symbols.add(h.symbol()));
        Map<String, StoredQuote> prices = quotes.latest(symbols);

        Map<UUID, BigDecimal> positionsValue = new HashMap<>();
        Set<UUID> unpriced = new HashSet<>();
        for (Holding h : holdings) {
            StoredQuote quote = prices.get(h.symbol());
            if (quote == null) {
                unpriced.add(h.account());
            } else {
                positionsValue.merge(h.account(), h.qty().multiply(quote.tick().last().value()), BigDecimal::add);
            }
        }
        List<Object[]> rows = new ArrayList<>();
        for (UUID account : accounts) {
            if (unpriced.contains(account)) {
                continue;
            }
            long value = cash.getOrDefault(account, 0L)
                    + Money.roundHalfEven(positionsValue.getOrDefault(account, BigDecimal.ZERO)).cents();
            rows.add(new Object[]{account, Timestamp.from(bucket), value});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO portfolio_snapshots (account_id, ts, value_cents) VALUES (?, ?, ?)
                ON CONFLICT (account_id, ts) DO NOTHING""", rows);
        return rows.size();
    }
}
