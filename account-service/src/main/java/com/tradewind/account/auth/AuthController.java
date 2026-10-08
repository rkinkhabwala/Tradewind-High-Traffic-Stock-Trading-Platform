package com.tradewind.account.auth;

import com.tradewind.account.auth.AuthDtos.LoginRequest;
import com.tradewind.account.auth.AuthDtos.RegisterRequest;
import com.tradewind.account.auth.AuthDtos.RegisterResponse;
import com.tradewind.account.token.InvalidRefreshTokenException;
import com.tradewind.account.token.TokenPair;
import com.tradewind.account.token.TokenService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auth endpoints are exempt from Idempotency-Key (approved decision): unique email covers register retries.
 * The refresh token never appears in a response body: it travels only in an httpOnly, SameSite=Strict cookie
 * scoped to /api/auth (approved Phase 2 decision 9), so page scripts cannot read it. The access token is returned
 * in the body and kept in memory by the client.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    /** What the browser sees from login and refresh. */
    record AccessTokenResponse(String accessToken, String tokenType, long expiresIn) {
        static AccessTokenResponse of(TokenPair pair) {
            return new AccessTokenResponse(pair.accessToken(), pair.tokenType(), pair.expiresIn());
        }
    }

    private final AuthService authService;
    private final TokenService tokenService;
    private final RefreshCookie refreshCookie;

    AuthController(AuthService authService, TokenService tokenService, RefreshCookie refreshCookie) {
        this.authService = authService;
        this.tokenService = tokenService;
        this.refreshCookie = refreshCookie;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    ResponseEntity<AccessTokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return withRefreshCookie(authService.login(request));
    }

    /** Rotates the refresh token: the presented one is revoked and a new cookie is set. */
    @PostMapping("/refresh")
    ResponseEntity<AccessTokenResponse> refresh(
            @CookieValue(name = RefreshCookie.NAME, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }
        return withRefreshCookie(tokenService.refresh(refreshToken));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@CookieValue(name = RefreshCookie.NAME, required = false) String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            tokenService.revoke(refreshToken);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookie.cleared().toString()).build();
    }

    private ResponseEntity<AccessTokenResponse> withRefreshCookie(TokenPair pair) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.of(pair.refreshToken()).toString())
                .body(AccessTokenResponse.of(pair));
    }
}
