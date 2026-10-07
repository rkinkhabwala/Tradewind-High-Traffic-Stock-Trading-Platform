package com.tradewind.account.token;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("tradewind.jwt")
public record JwtProperties(String issuer, Duration accessTokenTtl, Duration refreshTokenTtl, String privateKey) {
}
