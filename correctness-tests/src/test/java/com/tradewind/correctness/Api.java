package com.tradewind.correctness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradewind.common.event.EventJson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** HTTP calls the way the gateway makes them (identity in X-Account-Id), straight to the services. */
final class Api {

    record Response(int status, Map<String, Object> body) {
        String text(String field) {
            Object value = body.get(field);
            return value == null ? null : value.toString();
        }
    }

    private static final ObjectMapper JSON = EventJson.newMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final List<ServiceProcess> orders;
    private final ServiceProcess portfolio;

    Api(List<ServiceProcess> orders, ServiceProcess portfolio) {
        this.orders = orders;
        this.portfolio = portfolio;
    }

    /**
     * {@code instance} picks the order-service instance (requests for one key may hit different instances).
     * Like a real client, retries 503 / 409 + Retry-After with the same Idempotency-Key (the API contract): the
     * retry resumes the same order. Retries alternate instances, as a load balancer would.
     */
    Response placeOrder(int instance, UUID account, String key, Map<String, Object> order) {
        return withRetries(instance, i -> send(HttpRequest.newBuilder(uri(orders.get(i % orders.size()), "/api/orders"))
                .header("X-Account-Id", account.toString()).header("Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(order)))));
    }

    Response cancel(int instance, UUID account, String key, UUID orderId) {
        return withRetries(instance, i -> send(HttpRequest.newBuilder(uri(orders.get(i % orders.size()), "/api/orders/" + orderId))
                .header("X-Account-Id", account.toString()).header("Idempotency-Key", key).DELETE()));
    }

    /** How many requests needed a retry; reported so retries cannot silently hide a real problem. */
    final java.util.concurrent.atomic.AtomicInteger retries = new java.util.concurrent.atomic.AtomicInteger();

    private Response withRetries(int instance, java.util.function.IntFunction<Response> call) {
        Response response = call.apply(instance);
        for (int attempt = 1; attempt <= 10 && (response.status() == 503 || response.status() == 409); attempt++) {
            retries.incrementAndGet();
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return response;
            }
            response = call.apply(instance + attempt);
        }
        return response;
    }

    Response order(UUID account, UUID orderId) {
        return send(HttpRequest.newBuilder(uri(orders.getFirst(), "/api/orders/" + orderId))
                .header("X-Account-Id", account.toString()).GET());
    }

    void deposit(UUID account, long cents) {
        Response r = send(HttpRequest.newBuilder(uri(portfolio, "/internal/ledger/deposits"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(Map.of("referenceId", UUID.randomUUID(),
                        "accountId", account, "amountCents", cents)))));
        if (r.status() != 200) {
            throw new IllegalStateException("deposit failed: " + r);
        }
    }

    Response balances(UUID account) {
        return send(HttpRequest.newBuilder(uri(portfolio, "/internal/accounts/" + account + "/balances")).GET());
    }

    Response reconciliation() {
        return send(HttpRequest.newBuilder(uri(portfolio, "/internal/reconciliation")).GET());
    }

    @SuppressWarnings("unchecked")
    private Response send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = http.send(request.timeout(Duration.ofSeconds(15)).build(),
                    HttpResponse.BodyHandlers.ofString());
            Map<String, Object> body = response.body() == null || response.body().isBlank()
                    ? Map.of() : JSON.readValue(response.body(), Map.class);
            return new Response(response.statusCode(), body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static URI uri(ServiceProcess service, String path) {
        return URI.create(service.baseUrl() + path);
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
