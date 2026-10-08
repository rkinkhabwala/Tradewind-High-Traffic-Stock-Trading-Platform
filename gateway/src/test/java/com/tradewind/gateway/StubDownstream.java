package com.tradewind.gateway;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Stands in for every downstream service: serves the JWKS like account-service and echoes back the
 * identity and request-id headers the gateway forwarded, so tests can assert what services receive.
 */
final class StubDownstream {

    private final HttpServer server;

    StubDownstream(RSAKey signingKey) throws IOException {
        String jwks = new JWKSet(signingKey.toPublicJWK()).toString();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/.well-known/jwks.json", exchange -> respond(exchange, jwks));
        server.createContext("/", exchange -> respond(exchange, echo(exchange)));
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    private static String echo(HttpExchange exchange) {
        var headers = exchange.getRequestHeaders();
        return "{\"method\":\"%s\",\"path\":\"%s\",\"query\":%s,\"userId\":%s,\"accountId\":%s,\"requestId\":%s}".formatted(
                exchange.getRequestMethod(), exchange.getRequestURI().getPath(), quoted(exchange.getRequestURI().getRawQuery()),
                quoted(headers.getFirst("X-User-Id")), quoted(headers.getFirst("X-Account-Id")),
                quoted(headers.getFirst("X-Request-Id")));
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
