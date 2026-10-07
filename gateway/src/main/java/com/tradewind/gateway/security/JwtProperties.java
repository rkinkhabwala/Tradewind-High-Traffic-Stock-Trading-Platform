package com.tradewind.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tradewind.jwt")
public record JwtProperties(String issuer, String jwkSetUri) {
}
