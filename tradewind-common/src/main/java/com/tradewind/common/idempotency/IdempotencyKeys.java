package com.tradewind.common.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Helpers shared by every endpoint that requires an {@code Idempotency-Key} (spec section 4).
 * Storage of {@code (account, key) -> response} lives in each service's own database.
 */
public final class IdempotencyKeys {

    public static final String HEADER = "Idempotency-Key";
    public static final Duration RETENTION = Duration.ofHours(24);
    public static final int MAX_LENGTH = 128;

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_\\-:.]{1," + MAX_LENGTH + "}");

    private IdempotencyKeys() {
    }

    /** Returns the key unchanged, or throws {@link InvalidIdempotencyKeyException} if missing or malformed. */
    public static String requireValid(String key) {
        if (key == null || key.isBlank()) {
            throw new InvalidIdempotencyKeyException(HEADER + " header is required");
        }
        if (!VALID.matcher(key).matches()) {
            throw new InvalidIdempotencyKeyException(
                    HEADER + " must be 1-" + MAX_LENGTH + " characters of [A-Za-z0-9_-:.]");
        }
        return key;
    }

    /**
     * Fingerprint of a request, stored with the key. A retry with the same key but a different fingerprint is a
     * client error, not a replay. Method and path are included so a key cannot be reused across endpoints.
     */
    public static String requestHash(String method, String path, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(method.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
