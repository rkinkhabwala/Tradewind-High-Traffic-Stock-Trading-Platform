package com.tradewind.marketdata.candle;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** Writes candle snapshots to Postgres every few seconds; on failure keeps them for the next attempt. */
@Component
class CandleFlusher {

    private static final Logger log = LoggerFactory.getLogger(CandleFlusher.class);

    private final CandleAggregator aggregator;
    private final CandleRepository repository;

    CandleFlusher(CandleAggregator aggregator, CandleRepository repository) {
        this.aggregator = aggregator;
        this.repository = repository;
    }

    /** Also runs once on shutdown so a restart does not lose the minute in progress. */
    @PreDestroy
    @Scheduled(fixedDelayString = "${tradewind.simulator.candle-flush-interval:5s}")
    void flush() {
        List<CandleUpdate> updates = aggregator.drain();
        if (updates.isEmpty()) {
            return;
        }
        try {
            repository.upsert(updates);
        } catch (DataAccessException e) {
            log.warn("Candle flush of {} updates failed, will retry: {}", updates.size(), e.toString());
            aggregator.requeue(updates);
        }
    }
}
