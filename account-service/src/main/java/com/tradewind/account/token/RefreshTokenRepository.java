package com.tradewind.account.token;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    /** Compare-and-set: returns 1 for exactly one caller even when several race on the same token. */
    @Modifying
    @Query("update RefreshTokenEntity t set t.revoked = true where t.id = :id and t.revoked = false")
    int revokeIfActive(@Param("id") UUID id);

    @Modifying
    @Query("update RefreshTokenEntity t set t.revoked = true where t.userId = :userId and t.revoked = false")
    int revokeAllForUser(@Param("userId") UUID userId);
}
