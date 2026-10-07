package com.tradewind.account.token;

public record TokenPair(String accessToken, String refreshToken, String tokenType, long expiresIn) {
}
