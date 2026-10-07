package com.tradewind.account.funding;

import com.tradewind.common.idempotency.JdbcIdempotentExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;

@Configuration
@EnableScheduling
class IdempotencyConfig {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyConfig.class);

    @Bean
    JdbcIdempotentExecutor idempotentExecutor(JdbcClient jdbc, Clock clock) {
        return new JdbcIdempotentExecutor(jdbc, clock);
    }

    @Bean
    IdempotencyPurger idempotencyPurger(JdbcIdempotentExecutor executor) {
        return new IdempotencyPurger(executor);
    }

    /** Drops keys past the 24h retention window. */
    static class IdempotencyPurger {
        private final JdbcIdempotentExecutor executor;

        IdempotencyPurger(JdbcIdempotentExecutor executor) {
            this.executor = executor;
        }

        @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
        void purge() {
            int purged = executor.purgeExpired();
            if (purged > 0) {
                log.info("Purged {} expired idempotency keys", purged);
            }
        }
    }
}
