package com.tradewind.account.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    /** BCrypt only uses the first 72 bytes, so longer passwords are rejected rather than silently truncated. */
    public record RegisterRequest(@NotBlank @Email @Size(max = 254) String email,
                                  @NotBlank @Size(min = 8, max = 72) String password) {
    }

    public record RegisterResponse(UUID userId, UUID accountId, String email) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }
}
