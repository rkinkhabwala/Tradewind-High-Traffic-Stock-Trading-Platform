package com.tradewind.account.token;

import com.tradewind.account.user.AccountEntity;
import com.tradewind.account.user.AccountRepository;
import com.tradewind.account.user.AccountStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Access + refresh token lifecycle. Refresh tokens rotate on every use; presenting an already-rotated token
 * is treated as theft and revokes every refresh token of that user.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private final RefreshTokenRepository refreshTokens;
    private final AccountRepository accounts;
    private final JwtIssuer jwtIssuer;
    private final JwtProperties properties;
    private final Clock clock;

    public TokenService(RefreshTokenRepository refreshTokens, AccountRepository accounts, JwtIssuer jwtIssuer,
                        JwtProperties properties, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.accounts = accounts;
        this.jwtIssuer = jwtIssuer;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public TokenPair issue(UUID userId, UUID accountId) {
        Instant now = clock.instant();
        String refreshToken = RefreshTokens.generate();
        refreshTokens.save(new RefreshTokenEntity(UUID.randomUUID(), userId, RefreshTokens.hash(refreshToken),
                now.plus(properties.refreshTokenTtl()), now));
        return new TokenPair(jwtIssuer.issue(userId, accountId), refreshToken, "Bearer",
                properties.accessTokenTtl().toSeconds());
    }

    /** Commits the reuse-detection revocation even though the caller gets an error. */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public TokenPair refresh(String refreshToken) {
        RefreshTokenEntity current = refreshTokens.findByTokenHash(RefreshTokens.hash(refreshToken))
                .orElseThrow(InvalidRefreshTokenException::new);
        if (current.isRevoked()) {
            int revoked = refreshTokens.revokeAllForUser(current.getUserId());
            log.warn("Refresh token reuse for user {}; revoked {} active tokens", current.getUserId(), revoked);
            throw new InvalidRefreshTokenException();
        }
        if (!current.getExpiresAt().isAfter(clock.instant())) {
            throw new InvalidRefreshTokenException();
        }
        if (refreshTokens.revokeIfActive(current.getId()) != 1) {
            // A concurrent refresh with the same token won the race.
            throw new InvalidRefreshTokenException();
        }
        AccountEntity account = accounts.findByUserId(current.getUserId())
                .orElseThrow(InvalidRefreshTokenException::new);
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new InvalidRefreshTokenException();
        }
        return issue(current.getUserId(), account.getId());
    }

    /** Idempotent: unknown or already-revoked tokens are ignored. */
    @Transactional
    public void revoke(String refreshToken) {
        refreshTokens.findByTokenHash(RefreshTokens.hash(refreshToken))
                .ifPresent(token -> refreshTokens.revokeIfActive(token.getId()));
    }
}
