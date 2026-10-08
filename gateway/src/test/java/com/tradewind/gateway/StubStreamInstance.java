package com.tradewind.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** A stream-service stand-in: a health endpoint that can be turned DOWN, and /ws answering with its name. */
final class StubStreamInstance {

    final String name;
    volatile boolean healthy = true;
    private final HttpServer server;

    StubStreamInstance(String name) throws IOException {
        this.name = name;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/actuator/health", exchange ->
                respond(exchange, healthy ? 200 : 503, healthy ? "{\"status\":\"UP\"}" : "{\"status\":\"DOWN\"}"));
        server.createContext("/ws", exchange -> respond(exchange, 200, "{\"instance\":\"" + name + "\"}"));
        server.start();
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    void stop() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
