package com.tradewind.account.user;

import com.tradewind.account.persistence.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class AccountEntity extends AssignedIdEntity {

    @Id
    private UUID id;
    private UUID userId;
    @Enumerated(EnumType.STRING)
    private AccountStatus status;
    private Instant createdAt;

    protected AccountEntity() {
    }

    public AccountEntity(UUID id, UUID userId, AccountStatus status, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.status = status;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }
}
