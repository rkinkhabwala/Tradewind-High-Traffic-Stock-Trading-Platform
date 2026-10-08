package com.tradewind.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Minimal blocking client of the public API for setup work (not for measured load). */
final class Api {

    static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String base;

    Api(String base) {
        this.base = base;
    }

    record Response(int status, JsonNode body) {
    }

    Response post(String path, String token, Object body, boolean idempotent) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body == null ? Map.of() : body)));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (idempotent) {
            request.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        return send(request.build());
    }

    Response get(String path, String token) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(base + path)).timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token).GET().build());
    }

    private Response send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String text = response.body();
        return new Response(response.statusCode(), text == null || text.isBlank() ? null : JSON.readTree(text));
    }
}
