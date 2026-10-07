package com.tradewind.marketdata.candle;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Repository
class CandleRepository {

    record CandleRow(Instant start, java.math.BigDecimal open, java.math.BigDecimal high, java.math.BigDecimal low,
                     java.math.BigDecimal close, long volume) {
    }

    record DailyRow(LocalDate session, java.math.BigDecimal open, java.math.BigDecimal high, java.math.BigDecimal low,
                    java.math.BigDecimal close, long volume) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbc;

    CandleRepository(JdbcTemplate jdbcTemplate, JdbcClient jdbc) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbc = jdbc;
    }

    /** Merge semantics: first open wins, high/low widen, latest close wins, volume deltas add up. */
    void upsert(List<CandleUpdate> updates) {
        jdbcTemplate.batchUpdate("""
                        INSERT INTO candles_1m (symbol, bucket_start, open, high, low, close, volume)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (symbol, bucket_start) DO UPDATE SET
                            high = GREATEST(candles_1m.high, EXCLUDED.high),
                            low = LEAST(candles_1m.low, EXCLUDED.low),
                            close = EXCLUDED.close,
                            volume = candles_1m.volume + EXCLUDED.volume""",
                updates, 500, (ps, u) -> {
                    ps.setString(1, u.symbol());
                    ps.setTimestamp(2, Timestamp.from(u.bucketStart()));
                    ps.setBigDecimal(3, u.open().value());
                    ps.setBigDecimal(4, u.high().value());
                    ps.setBigDecimal(5, u.low().value());
                    ps.setBigDecimal(6, u.close().value());
                    ps.setLong(7, u.volumeDelta());
                });
    }

    /** 1m/5m/1h: aggregate minute candles into fixed-width bins. */
    List<CandleRow> binned(String symbol, String step, Instant from, Instant to, int limit) {
        return jdbc.sql("""
                        SELECT date_bin(CAST(:step AS INTERVAL), bucket_start, TIMESTAMPTZ '2000-01-03 00:00:00+00') AS start,
                               (array_agg(open ORDER BY bucket_start))[1] AS open,
                               max(high) AS high, min(low) AS low,
                               (array_agg(close ORDER BY bucket_start DESC))[1] AS close,
                               sum(volume) AS volume
                        FROM candles_1m
                        WHERE symbol = :symbol AND bucket_start >= :from AND bucket_start < :to
                        GROUP BY 1 ORDER BY 1 LIMIT :limit""")
                .param("step", step).param("symbol", symbol)
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).param("limit", limit)
                .query((rs, n) -> new CandleRow(rs.getTimestamp("start").toInstant(), rs.getBigDecimal("open"),
                        rs.getBigDecimal("high"), rs.getBigDecimal("low"), rs.getBigDecimal("close"), rs.getLong("volume")))
                .list();
    }

    /** 1d: one candle per New York trading session. */
    List<DailyRow> daily(String symbol, Instant from, Instant to, int limit) {
        return jdbc.sql("""
                        SELECT CAST(bucket_start AT TIME ZONE 'America/New_York' AS DATE) AS session,
                               (array_agg(open ORDER BY bucket_start))[1] AS open,
                               max(high) AS high, min(low) AS low,
                               (array_agg(close ORDER BY bucket_start DESC))[1] AS close,
                               sum(volume) AS volume
                        FROM candles_1m
                        WHERE symbol = :symbol AND bucket_start >= :from AND bucket_start < :to
                        GROUP BY 1 ORDER BY 1 LIMIT :limit""")
                .param("symbol", symbol).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .param("limit", limit)
                .query((rs, n) -> new DailyRow(rs.getDate("session").toLocalDate(), rs.getBigDecimal("open"),
                        rs.getBigDecimal("high"), rs.getBigDecimal("low"), rs.getBigDecimal("close"), rs.getLong("volume")))
                .list();
    }
}
