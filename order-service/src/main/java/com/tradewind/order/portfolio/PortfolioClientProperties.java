package com.tradewind.order.portfolio;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("tradewind.portfolio")
public record PortfolioClientProperties(String url, Duration connectTimeout, Duration readTimeout,
                                        int maxConcurrentCalls, int failureRateThresholdPercent,
                                        Duration slowCallThreshold, Duration openStateWait) {
}
