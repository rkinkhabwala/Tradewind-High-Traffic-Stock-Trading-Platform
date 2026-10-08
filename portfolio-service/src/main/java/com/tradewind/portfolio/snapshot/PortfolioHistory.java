package com.tradewind.portfolio.snapshot;

import com.tradewind.common.clock.MarketClock;
import com.tradewind.common.clock.MarketHours;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Reads the history chart from {@code portfolio_snapshots}. 1D returns the raw 5-minute points of the latest
 * session; longer ranges are downsampled in SQL to the last value per bin, so a 1Y request returns ~250 points
 * instead of tens of thousands. The client appends the live portfolio value as the final point.
 */
@Service
public class PortfolioHistory {

    public enum Range {
        D1("1D", null, null),
        W1("1W", Duration.ofDays(7), "30 minutes"),
        M1("1M", Duration.ofDays(30), "2 hours"),
        Y1("1Y", Duration.ofDays(365), "1 day");

        public final String code;
        final Duration span;
        final String bin;

        Range(String code, Duration span, String bin) {
            this.code = code;
            this.span = span;
            this.bin = bin;
        }

        public static Range parse(String code) {
            return Arrays.stream(values()).filter(r -> r.code.equalsIgnoreCase(code)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("range must be one of 1D, 1W, 1M, 1Y"));
        }
    }

    public record Point(Instant ts, long valueCents) {
    }

    private final JdbcClient jdbc;
    private final MarketClock clock;

    PortfolioHistory(JdbcClient jdbc, MarketClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<Point> history(UUID accountId, Range range) {
        if (range == Range.D1) {
            Instant latest = jdbc.sql("SELECT max(ts) FROM portfolio_snapshots WHERE account_id = :a")
                    .param("a", accountId).query(Timestamp.class).optional().map(Timestamp::toInstant).orElse(null);
            if (latest == null) {
                return List.of();
            }
            LocalDate session = latest.atZone(MarketHours.ZONE).toLocalDate();
            Instant open = session.atTime(MarketHours.OPEN).atZone(MarketHours.ZONE).toInstant();
            return jdbc.sql("SELECT ts, value_cents FROM portfolio_snapshots WHERE account_id = :a AND ts >= :from ORDER BY ts")
                    .param("a", accountId).param("from", Timestamp.from(open))
                    .query((rs, n) -> new Point(rs.getTimestamp(1).toInstant(), rs.getLong(2)))
                    .list();
        }
        Instant from = clock.now().minus(range.span);
        return jdbc.sql("""
                        SELECT date_bin(CAST(:bin AS INTERVAL), ts, TIMESTAMPTZ '2000-01-03 00:00:00+00') AS bucket,
                               (array_agg(value_cents ORDER BY ts DESC))[1] AS value_cents
                        FROM portfolio_snapshots
                        WHERE account_id = :a AND ts >= :from
                        GROUP BY 1 ORDER BY 1""")
                .param("bin", range.bin).param("a", accountId).param("from", Timestamp.from(from))
                .query((rs, n) -> new Point(rs.getTimestamp(1).toInstant(), rs.getLong(2)))
                .list();
    }
}
