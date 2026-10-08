package com.tradewind.marketdata.simulator;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Internal (not routed by the gateway): temporarily multiply every symbol's tick rate, e.g.
 * {@code POST /internal/simulator/rate {"multiplier": 3.6, "durationSeconds": 60}} for the 10,000 ticks/s burst.
 * The multiplier is exposed as {@code marketdata.tick.rate.multiplier}.
 */
@RestController
class SimulatorController {

    record RateRequest(Double multiplier, Long durationSeconds) {
    }

    record RateView(double multiplier, long remainingSeconds) {
    }

    @Configuration
    static class Beans {
        @Bean
        TickRate tickRate(MeterRegistry meters) {
            TickRate rate = new TickRate();
            Gauge.builder("marketdata.tick.rate.multiplier", rate, r -> r.multiplier(System.nanoTime())).register(meters);
            return rate;
        }
    }

    private final TickRate rate;

    SimulatorController(TickRate rate) {
        this.rate = rate;
    }

    @PostMapping("/internal/simulator/rate")
    RateView boost(@RequestBody RateRequest request) {
        if (request == null || request.multiplier() == null || request.durationSeconds() == null) {
            throw new IllegalArgumentException("multiplier and durationSeconds are required");
        }
        rate.boost(request.multiplier(), Duration.ofSeconds(request.durationSeconds()), System.nanoTime());
        return current();
    }

    @GetMapping("/internal/simulator/rate")
    RateView current() {
        long now = System.nanoTime();
        return new RateView(rate.multiplier(now), rate.remaining(now).toSeconds());
    }
}
