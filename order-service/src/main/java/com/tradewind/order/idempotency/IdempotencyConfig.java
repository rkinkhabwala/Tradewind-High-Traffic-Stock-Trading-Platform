package com.tradewind.order.idempotency;

import com.tradewind.common.idempotency.CachingIdempotentExecutor;
import com.tradewind.common.idempotency.IdempotencyKeys;
import com.tradewind.common.idempotency.IdempotencyResponseCache;
import com.tradewind.common.idempotency.JdbcIdempotentExecutor;
import com.tradewind.common.idempotency.StoredResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Configuration
class IdempotencyConfig {

    @Bean
    JdbcIdempotentExecutor jdbcIdempotentExecutor(JdbcClient jdbc, Clock clock) {
        return new JdbcIdempotentExecutor(jdbc, clock);
    }

    @Bean
    CachingIdempotentExecutor idempotentExecutor(JdbcIdempotentExecutor jdbcExecutor, StringRedisTemplate redis) {
        return new CachingIdempotentExecutor(jdbcExecutor, new RedisResponseCache(redis));
    }

    @Bean
    Purger idempotencyPurger(JdbcIdempotentExecutor executor) {
        return new Purger(executor);
    }

    static class Purger {
        private final JdbcIdempotentExecutor executor;

        Purger(JdbcIdempotentExecutor executor) {
            this.executor = executor;
        }

        @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
        void purge() {
            executor.purgeExpired();
        }
    }

    /** {@code idem:{account}:{key}} hash with the same 24h lifetime as the Postgres record. */
    static class RedisResponseCache implements IdempotencyResponseCache {
        private final StringRedisTemplate redis;

        RedisResponseCache(StringRedisTemplate redis) {
            this.redis = redis;
        }

        @Override
        public Optional<CachedResponse> get(UUID accountId, String key) {
            Map<Object, Object> hash = redis.opsForHash().entries(redisKey(accountId, key));
            if (hash.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new CachedResponse((String) hash.get("hash"),
                    new StoredResponse(Integer.parseInt((String) hash.get("status")), (String) hash.get("body"))));
        }

        @Override
        public void put(UUID accountId, String key, CachedResponse response) {
            String redisKey = redisKey(accountId, key);
            redis.opsForHash().putAll(redisKey, Map.of("hash", response.requestHash(),
                    "status", Integer.toString(response.response().status()), "body", response.response().body()));
            redis.expire(redisKey, IdempotencyKeys.RETENTION);
        }

        static String redisKey(UUID accountId, String key) {
            return "idem:" + accountId + ":" + key;
        }
    }
}
