package com.tradewind.account.auth;

import com.tradewind.account.auth.AuthDtos.LoginRequest;
import com.tradewind.account.auth.AuthDtos.RefreshRequest;
import com.tradewind.account.auth.AuthDtos.RegisterRequest;
import com.tradewind.account.auth.AuthDtos.RegisterResponse;
import com.tradewind.account.token.TokenPair;
import com.tradewind.account.token.TokenService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Auth endpoints are exempt from Idempotency-Key (approved decision): unique email covers register retries. */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final AuthService authService;
    private final TokenService tokenService;

    AuthController(AuthService authService, TokenService tokenService) {
        this.authService = authService;
        this.tokenService = tokenService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    TokenPair login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    TokenPair refresh(@Valid @RequestBody RefreshRequest request) {
        return tokenService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@Valid @RequestBody RefreshRequest request) {
        tokenService.revoke(request.refreshToken());
    }
}
