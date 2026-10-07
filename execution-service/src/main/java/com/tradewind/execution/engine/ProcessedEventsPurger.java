package com.tradewind.execution.engine;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Keeps processed_events bounded. Seven days is far beyond any redelivery window (orders.submitted offsets are
 * committed within milliseconds of processing).
 */
@Component
class ProcessedEventsPurger {

    static final Duration RETENTION = Duration.ofDays(7);

    private final ExecutionRepository repository;
    private final Clock clock;

    ProcessedEventsPurger(ExecutionRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    void purge() {
        repository.purgeProcessedBefore(clock.instant().minus(RETENTION));
    }
}
