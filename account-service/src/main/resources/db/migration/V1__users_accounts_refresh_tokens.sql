CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL
);

CREATE TABLE accounts (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL UNIQUE REFERENCES users (id),
    status     VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'LOCKED')),
    created_at TIMESTAMPTZ NOT NULL
);

-- Only a SHA-256 hash of each refresh token is stored.
CREATE TABLE refresh_tokens (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX refresh_tokens_active_by_user ON refresh_tokens (user_id) WHERE NOT revoked;
