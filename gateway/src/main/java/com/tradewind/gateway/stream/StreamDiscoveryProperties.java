package com.tradewind.gateway.stream;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Where stream-service instances are found: every address the host name resolves to (Docker's DNS returns one per
 * scaled container) at a fixed port, re-resolved and health-checked every {@code refreshInterval}.
 */
@ConfigurationProperties("tradewind.stream.discovery")
public record StreamDiscoveryProperties(
        @DefaultValue("localhost") String host,
        @DefaultValue("8086") int port,
        @DefaultValue("2s") Duration refreshInterval,
        @DefaultValue("1s") Duration healthTimeout,
        @DefaultValue("/actuator/health") String healthPath) {
}
