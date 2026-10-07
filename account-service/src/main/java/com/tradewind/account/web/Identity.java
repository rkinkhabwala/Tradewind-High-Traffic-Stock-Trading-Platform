package com.tradewind.account.web;

import java.util.UUID;

/** Identity headers injected by the gateway after it validated the JWT (client-supplied values are stripped). */
public final class Identity {

    public static final String ACCOUNT_ID_HEADER = "X-Account-Id";

    private Identity() {
    }

    public static UUID require(UUID accountId) {
        if (accountId == null) {
            throw new MissingIdentityException();
        }
        return accountId;
    }

    public static class MissingIdentityException extends RuntimeException {
        public MissingIdentityException() {
            super("Request did not come through the gateway with an authenticated identity");
        }
    }
}
