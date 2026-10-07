package com.tradewind.execution.clock;

import com.tradewind.common.clock.MarketClock;
import com.tradewind.common.clock.MarketClocks;
import com.tradewind.common.clock.SharedClockAnchor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

/** Same shared market clock as every other service (anchor in Redis, see SharedClockAnchor). */
@Configuration
class ClockConfig {

    static final String ANCHOR_KEY = "market:clock-anchor";

    @ConfigurationProperties("tradewind.clock")
    record ClockProperties(MarketClocks.Mode mode, long speed, String simStart, String realEpoch) {
        SharedClockAnchor.Settings settings() {
            Instant epoch = realEpoch == null || realEpoch.isBlank() ? null : OffsetDateTime.parse(realEpoch).toInstant();
            return new SharedClockAnchor.Settings(mode, speed, OffsetDateTime.parse(simStart).toInstant(), epoch);
        }
    }

    @Bean
    MarketClock marketClock(ClockProperties properties, StringRedisTemplate redis, Clock clock) {
        SharedClockAnchor.AnchorStore store = new SharedClockAnchor.AnchorStore() {
            @Override
            public Optional<String> get() {
                return Optional.ofNullable(redis.opsForValue().get(ANCHOR_KEY));
            }

            @Override
            public boolean putIfAbsent(String value) {
                return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(ANCHOR_KEY, value));
            }
        };
        return SharedClockAnchor.resolve(properties.settings(), store, clock);
    }
}
