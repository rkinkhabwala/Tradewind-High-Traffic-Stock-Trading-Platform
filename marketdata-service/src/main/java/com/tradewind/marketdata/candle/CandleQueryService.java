package com.tradewind.marketdata.candle;

import com.tradewind.common.clock.MarketClock;
import com.tradewind.common.clock.MarketHours;
import com.tradewind.common.money.Price;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Service
public class CandleQueryService {

    public enum Interval {
        M1("1m", "1 minute", Duration.ofMinutes(1)),
        M5("5m", "5 minutes", Duration.ofMinutes(5)),
        H1("1h", "1 hour", Duration.ofHours(1)),
        D1("1d", null, Duration.ofDays(1));

        final String code;
        final String sqlStep;
        final Duration width;

        Interval(String code, String sqlStep, Duration width) {
            this.code = code;
            this.sqlStep = sqlStep;
            this.width = width;
        }

        public static Interval parse(String code) {
            return Arrays.stream(values()).filter(i -> i.code.equals(code)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("interval must be one of 1m, 5m, 1h, 1d"));
        }
    }

    /** Prices as plain decimal strings; {@code start} is the bin start (session open for 1d). */
    public record Candle(Instant start, String open, String high, String low, String close, long volume) {
    }

    static final int DEFAULT_CANDLES = 300;
    static final int MAX_CANDLES = 2_000;

    private final CandleRepository repository;
    private final MarketClock clock;

    CandleQueryService(CandleRepository repository, MarketClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<Candle> candles(String symbol, Interval interval, Instant from, Instant to) {
        Instant end = to != null ? to : clock.now();
        Instant start = from != null ? from : end.minus(interval.width.multipliedBy(DEFAULT_CANDLES));
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (Duration.between(start, end).dividedBy(interval.width) > MAX_CANDLES) {
            throw new IllegalArgumentException("range too large: at most " + MAX_CANDLES + " candles per request");
        }
        if (interval == Interval.D1) {
            return repository.daily(symbol, start, end, MAX_CANDLES).stream()
                    .map(r -> new Candle(r.session().atTime(MarketHours.OPEN).atZone(MarketHours.ZONE).toInstant(),
                            plain(r.open()), plain(r.high()), plain(r.low()), plain(r.close()), r.volume()))
                    .toList();
        }
        return repository.binned(symbol, interval.sqlStep, start, end, MAX_CANDLES).stream()
                .map(r -> new Candle(r.start(), plain(r.open()), plain(r.high()), plain(r.low()), plain(r.close()),
                        r.volume()))
                .toList();
    }

    private static String plain(BigDecimal price) {
        return Price.of(price).toJson();
    }
}
