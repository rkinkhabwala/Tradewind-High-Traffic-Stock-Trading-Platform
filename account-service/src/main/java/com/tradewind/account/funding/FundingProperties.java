package com.tradewind.account.funding;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param maxAmountCents largest single simulated deposit or withdrawal (guards against typos and overflow)
 */
@ConfigurationProperties("tradewind.funding")
public record FundingProperties(String portfolioUrl, Duration connectTimeout, Duration readTimeout,
                                long maxAmountCents) {
}
