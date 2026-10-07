package com.tradewind.common.web;

import java.util.UUID;
import java.util.regex.Pattern;

/** {@code X-Request-Id} propagation (spec section 7): accepted if well-formed, otherwise replaced. */
public final class RequestIds {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_\\-.:]{1,128}");

    private RequestIds() {
    }

    public static String acceptOrGenerate(String incoming) {
        return incoming != null && VALID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
    }
}
