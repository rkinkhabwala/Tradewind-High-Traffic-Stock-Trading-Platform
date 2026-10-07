package com.tradewind.marketdata.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Simulation parameters. Floating point is fine here: these drive a random process, and prices are rounded to
 * whole cents before they leave the simulator.
 */
@ConfigurationProperties("tradewind.simulator")
public record SimulatorProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1") int tickRateMin,
        @DefaultValue("10") int tickRateMax,
        @DefaultValue("") String hotSymbol,
        @DefaultValue("100") int hotTickRate,
        @DefaultValue("5") double hotVolatilityMultiplier,
        /* Annualised drift of the GBM. */
        @DefaultValue("0.05") double drift,
        /* Bid/ask spread in basis points of the mid price (minimum one cent). */
        @DefaultValue("4") double spreadBps,
        /* Probability that a tick includes a price jump, and the jump's log-return standard deviation. */
        @DefaultValue("0.0005") double jumpProbability,
        @DefaultValue("0.02") double jumpVolatility,
        /* 0 = random seed; any other value makes the price paths reproducible. */
        @DefaultValue("0") long seed,
        @DefaultValue("5ms") Duration loopInterval,
        @DefaultValue("5s") Duration candleFlushInterval,
        @DefaultValue("1000") int sinkQueueCapacity) {

    public SimulatorProperties {
        if (tickRateMin < 1 || tickRateMax < tickRateMin) {
            throw new IllegalArgumentException("tick rates must satisfy 1 <= min <= max");
        }
    }

    public boolean hotModeFor(String symbol) {
        return hotSymbol != null && !hotSymbol.isBlank() && hotSymbol.equalsIgnoreCase(symbol);
    }
}
