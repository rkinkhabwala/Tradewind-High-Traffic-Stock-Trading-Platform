package com.tradewind.stream.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradewind.common.event.EventJson;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Plain JSON frames (spec 7). Client ops: {@code {"op":"subscribe"|"unsubscribe","symbols":[...]}} and
 * {@code {"op":"ping"}}. Server messages are tagged by {@code type}: hello, subscriptions, pong, error and quote.
 */
final class Protocol {

    record ClientOp(String op, List<String> symbols) {
    }

    record Hello(String type, String instanceId, long heartbeatSeconds) {
    }

    record Subscriptions(String type, Collection<String> symbols) {
    }

    record Pong(String type, Instant ts) {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Error(String type, String code, String message, Collection<String> symbols) {
    }

    private static final ObjectMapper JSON = EventJson.newMapper();

    private Protocol() {
    }

    static ClientOp read(String text) {
        try {
            return JSON.readValue(text, ClientOp.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    static String hello(String instanceId, long heartbeatSeconds) {
        return write(new Hello("hello", instanceId, heartbeatSeconds));
    }

    static String subscriptions(Collection<String> symbols) {
        return write(new Subscriptions("subscriptions", symbols));
    }

    static String pong() {
        return write(new Pong("pong", Instant.now()));
    }

    static String error(String code, String message) {
        return error(code, message, List.of());
    }

    static String error(String code, String message, Collection<String> symbols) {
        return write(new Error("error", code, message, symbols));
    }

    private static String write(Object message) {
        try {
            return JSON.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
