package com.tradewind.account.auth;

import com.tradewind.account.token.JwtProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The refresh-token cookie: httpOnly (no script access), SameSite=Strict (never sent cross-site, so no CSRF on
 * refresh/logout), Path=/api/auth (not sent with any other request). {@code Secure} is on by default; browsers
 * accept Secure cookies on http://localhost, so it only needs turning off for plain-http non-localhost hosts.
 */
@Component
class RefreshCookie {

    static final String NAME = "tw_refresh";
    static final String PATH = "/api/auth";

    private final Duration maxAge;
    private final boolean secure;

    RefreshCookie(JwtProperties jwt, @Value("${tradewind.auth.cookie-secure:true}") boolean secure) {
        this.maxAge = jwt.refreshTokenTtl();
        this.secure = secure;
    }

    ResponseCookie of(String refreshToken) {
        return base(refreshToken).maxAge(maxAge).build();
    }

    ResponseCookie cleared() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(secure).sameSite("Strict").path(PATH);
    }
}
