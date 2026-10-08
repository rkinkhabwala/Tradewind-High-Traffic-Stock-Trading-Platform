package com.tradewind.gateway.discovery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

/**
 * Horizontally scaled services the gateway load-balances itself (stream-service, order-service): every address the
 * host name resolves to (Docker's DNS returns one per scaled container) at a fixed port, re-resolved and
 * health-checked every {@code refreshInterval}. Routes reach them as {@code lb://<service id>}.
 */
@ConfigurationProperties("tradewind.discovery")
public record DiscoveryProperties(
        @DefaultValue("2s") Duration refreshInterval,
        @DefaultValue("1s") Duration healthTimeout,
        Map<String, Target> services) {

    public record Target(String host, int port, @DefaultValue("/actuator/health") String healthPath) {
    }

    public DiscoveryProperties {
        services = services == null ? Map.of() : Map.copyOf(services);
    }
}
