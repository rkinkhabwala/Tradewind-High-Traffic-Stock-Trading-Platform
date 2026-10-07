package com.tradewind.common.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.UncheckedIOException;

/**
 * The single JSON configuration for events and outbox payloads, so every service reads and writes them identically.
 * Unknown properties are ignored so consumers tolerate additive schema changes.
 */
public final class EventJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private EventJson() {
    }

    /** A copy of the shared configuration, for callers that need their own {@link ObjectMapper}. */
    public static ObjectMapper newMapper() {
        return MAPPER.copy();
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T> EventEnvelope<T> read(String json, Class<T> payloadType) {
        JavaType type = MAPPER.getTypeFactory().constructParametricType(EventEnvelope.class, payloadType);
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads the envelope without binding the payload, so a consumer can dispatch on {@code eventType} first. */
    public static EventEnvelope<JsonNode> readRaw(String json) {
        return read(json, JsonNode.class);
    }

    public static <T> T convert(JsonNode node, Class<T> type) {
        try {
            return MAPPER.treeToValue(node, type);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
