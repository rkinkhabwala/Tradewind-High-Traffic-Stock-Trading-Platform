package com.tradewind.order.idempotency;

import com.tradewind.common.idempotency.CachingIdempotentExecutor;
import com.tradewind.common.idempotency.IdempotencyKeys;
import com.tradewind.common.idempotency.IdempotencyResponseCache;
import com.tradewind.common.idempotency.JdbcIdempotentExecutor;
import com.tradewind.common.idempotency.StoredResponse;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
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

    /**
     * Idempotency bookkeeping (claim a key, record the response) on its own small pool whose sessions do not wait
     * for the WAL to reach disk (synchronous_commit=off), which saves two of the ~5 disk syncs per order. Safe here:
     * <ul>
     *   <li>a claim becomes durable with the order's own (fully durable) insert moments later: the WAL is sequential;</li>
     *   <li>a recorded response lost in a crash leaves a claim without a response; the retry takes it over after the
     *   abandon timeout, finds the order by its unique (account, client_order_id) and answers with that same order.</li>
     * </ul>
     * Orders, the outbox and everything else stay fully durable on the main pool. Not a DataSource bean, so Spring
     * Boot's primary DataSource is unaffected.
     */
    static final class IdempotencyDatabase implements AutoCloseable {
        final HikariDataSource dataSource;
        final JdbcClient jdbc;

        IdempotencyDatabase(JdbcConnectionDetails details, int poolSize, String synchronousCommit) {
            if (!java.util.Set.of("on", "off", "local").contains(synchronousCommit)) {
                throw new IllegalArgumentException("synchronous_commit must be on, off or local");
            }
            dataSource = new HikariDataSource();
            dataSource.setPoolName("idempotency");
            dataSource.setJdbcUrl(details.getJdbcUrl());
            dataSource.setUsername(details.getUsername());
            dataSource.setPassword(details.getPassword());
            dataSource.setMaximumPoolSize(poolSize);
            dataSource.setConnectionTimeout(3000);
            dataSource.addDataSourceProperty("options", "-c statement_timeout=5000 -c synchronous_commit=" + synchronousCommit);
            jdbc = JdbcClient.create(dataSource);
        }

        @Override
        public void close() {
            dataSource.close();
        }
    }

    @Bean(destroyMethod = "close")
    IdempotencyDatabase idempotencyDatabase(JdbcConnectionDetails details,
                                            @Value("${tradewind.idempotency.pool-size:10}") int poolSize,
                                            @Value("${tradewind.idempotency.synchronous-commit:off}") String synchronousCommit) {
        return new IdempotencyDatabase(details, poolSize, synchronousCommit);
    }

    @Bean
    JdbcIdempotentExecutor jdbcIdempotentExecutor(IdempotencyDatabase database, Clock clock) {
        return new JdbcIdempotentExecutor(database.jdbc, clock);
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
