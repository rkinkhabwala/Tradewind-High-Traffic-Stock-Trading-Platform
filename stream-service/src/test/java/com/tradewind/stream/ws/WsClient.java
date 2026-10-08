package com.tradewind.stream.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** A JDK WebSocket test client that collects server messages as JSON. */
class WsClient implements AutoCloseable {

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    final LinkedBlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
    final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
    final WebSocket socket;

    WsClient(int port, UUID userId, UUID accountId) {
        this.socket = HTTP.newWebSocketBuilder()
                .header("X-User-Id", userId.toString())
                .header("X-Account-Id", accountId.toString())
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    final StringBuilder partial = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        partial.append(data);
                        if (last) {
                            try {
                                messages.add(JSON.readTree(partial.toString()));
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                            partial.setLength(0);
                        }
                        ws.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                        closeCode.complete(statusCode);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        closeCode.completeExceptionally(error);
                    }
                }).join();
    }

    static WsClient connect(int port) {
        return new WsClient(port, UUID.randomUUID(), UUID.randomUUID());
    }

    void send(String json) {
        socket.sendText(json, true).join();
    }

    /** The next message matching the predicate within the timeout; earlier non-matching messages are discarded. */
    JsonNode await(Predicate<JsonNode> match, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long left = deadline - System.nanoTime();
            JsonNode next = left <= 0 ? null : messages.poll(left, TimeUnit.NANOSECONDS);
            if (next == null) {
                throw new AssertionError("no matching message within " + timeout);
            }
            if (match.test(next)) {
                return next;
            }
        }
    }

    JsonNode await(String type) throws InterruptedException {
        return await(m -> type.equals(m.path("type").asText()), Duration.ofSeconds(5));
    }

    /** Everything received within the window. */
    List<JsonNode> collect(Duration window) throws InterruptedException {
        List<JsonNode> all = new ArrayList<>();
        long deadline = System.nanoTime() + window.toNanos();
        long left;
        while ((left = deadline - System.nanoTime()) > 0) {
            JsonNode next = messages.poll(left, TimeUnit.NANOSECONDS);
            if (next != null) {
                all.add(next);
            }
        }
        return all;
    }

    static Predicate<JsonNode> quoteFor(String symbol) {
        return m -> "quote".equals(m.path("type").asText()) && symbol.equals(m.path("symbol").asText());
    }

    @Override
    public void close() {
        if (!socket.isOutputClosed()) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").exceptionally(e -> null);
        }
        socket.abort();
    }
}
