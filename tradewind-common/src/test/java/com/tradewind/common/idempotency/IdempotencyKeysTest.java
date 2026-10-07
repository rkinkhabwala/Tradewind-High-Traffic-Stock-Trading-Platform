package com.tradewind.common.idempotency;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeysTest {

    @Test
    void acceptsWellFormedKeys() {
        assertThat(IdempotencyKeys.requireValid("order-2026-10-07:abc_123.x")).isEqualTo("order-2026-10-07:abc_123.x");
        assertThat(IdempotencyKeys.requireValid("a".repeat(IdempotencyKeys.MAX_LENGTH))).hasSize(128);
    }

    @Test
    void rejectsMissingOrMalformedKeys() {
        assertThatThrownBy(() -> IdempotencyKeys.requireValid(null)).isInstanceOf(InvalidIdempotencyKeyException.class);
        assertThatThrownBy(() -> IdempotencyKeys.requireValid("  ")).isInstanceOf(InvalidIdempotencyKeyException.class);
        assertThatThrownBy(() -> IdempotencyKeys.requireValid("has space")).isInstanceOf(InvalidIdempotencyKeyException.class);
        assertThatThrownBy(() -> IdempotencyKeys.requireValid("a".repeat(129))).isInstanceOf(InvalidIdempotencyKeyException.class);
    }

    @Test
    void requestHashIsDeterministicAndCoversMethodPathAndBody() {
        byte[] body = "{\"symbol\":\"AAPL\"}".getBytes(StandardCharsets.UTF_8);
        String hash = IdempotencyKeys.requestHash("POST", "/api/orders", body);

        assertThat(hash).hasSize(64).isEqualTo(IdempotencyKeys.requestHash("POST", "/api/orders", body.clone()));
        assertThat(hash).isNotEqualTo(IdempotencyKeys.requestHash("POST", "/api/account/deposits", body));
        assertThat(hash).isNotEqualTo(IdempotencyKeys.requestHash("DELETE", "/api/orders", body));
        assertThat(hash).isNotEqualTo(IdempotencyKeys.requestHash("POST", "/api/orders",
                "{\"symbol\":\"TSLA\"}".getBytes(StandardCharsets.UTF_8)));
    }
}
