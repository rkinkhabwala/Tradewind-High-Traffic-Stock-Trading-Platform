package com.tradewind.common.idempotency;

/** The response recorded for an idempotency key; a retry gets exactly this back. */
public record StoredResponse(int status, String body) {
}
