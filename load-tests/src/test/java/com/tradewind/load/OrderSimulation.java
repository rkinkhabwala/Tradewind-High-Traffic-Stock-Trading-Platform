package com.tradewind.load;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * Shared base of the order simulations. Open workload model: every virtual user is one order from a random,
 * already logged-in load user, so the arrival rate is exactly the configured orders/sec whatever the latency.
 * Market buys of 1 share. 202 = placed; 429 = the per-user rate limit (intended under burst); anything else is
 * an error. Client latency is measured here; order-to-fill by {@link FillObserver}; the server view and the
 * report come from {@link Recorder} / {@link Prom}.
 */
abstract class OrderSimulation extends Simulation {

    static final HttpProtocolBuilder PROTOCOL = http.baseUrl(LoadConfig.gatewayUrl())
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("tradewind-load")
            .shareConnections();

    final Map<String, Object> settings = new LinkedHashMap<>();
    private long startMillis;

    /** One market buy of {@code symbol}; {@code group} labels its measurements (e.g. "hot", "other"). */
    static ChainBuilder placeOrder(String requestName, Function<Session, String> symbol, String group) {
        String label = group.isEmpty() ? "" : " " + group;
        return exec(session -> session
                .set("symbol", symbol.apply(session))
                .set("key", UUID.randomUUID().toString())
                .set("t0", System.nanoTime()))
                .exec(http(requestName).post("/api/orders")
                        .header("Authorization", "Bearer #{token}")
                        .header("Idempotency-Key", "#{key}")
                        .body(StringBody("{\"symbol\":\"#{symbol}\",\"side\":\"BUY\",\"type\":\"MARKET\",\"qty\":\"1\"}"))
                        .check(status().saveAs("status"), jsonPath("$.id").optional().saveAs("orderId"),
                                status().in(202, 429)))
                .exec(session -> {
                    long elapsed = System.nanoTime() - session.getLong("t0");
                    int status = session.contains("status") ? session.getInt("status") : 0;
                    Recorder.count("POST /api/orders " + status + label);
                    if (status == 202) {
                        Recorder.recordNanos("POST /api/orders (202)" + label, elapsed);
                        if (session.contains("orderId")) {
                            FillObserver.sent(session.getString("orderId"), session.getLong("t0"), group);
                        }
                    }
                    return session.remove("orderId").remove("status");
                });
    }

    boolean observeFills() {
        return LoadConfig.get("observeFills", "true").equals("true");
    }

    @Override
    public void before() {
        try {
            Tokens.login();
            if (observeFills()) {
                FillObserver.start(Tokens.users());
            }
        } catch (Exception e) {
            throw new IllegalStateException("load setup failed", e);
        }
        startMillis = System.currentTimeMillis();
    }

    @Override
    public void after() {
        long endMillis = System.currentTimeMillis();
        try {
            if (observeFills()) {
                Thread.sleep(3000);                              // late fills of the last orders
                Recorder.count("accepted orders whose fill was not pushed within 3 s of the end", FillObserver.unmatched());
                FillObserver.stop();
            }
            Thread.sleep(6000);                                  // one more Prometheus scrape of the end of the run
            Recorder.report(getClass().getSimpleName(), settings, startMillis, endMillis, Prom.snapshot(startMillis, endMillis));
        } catch (Exception e) {
            throw new IllegalStateException("report failed", e);
        }
    }
}
