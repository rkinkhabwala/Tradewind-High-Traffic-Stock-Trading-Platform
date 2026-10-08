package com.tradewind.load;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pace;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * Order placement through the gateway (spec 8 "order placement throughput", "order API latency"): each virtual
 * user is one funded load user that logs in once, then places market buys of 1 share of a random liquid symbol
 * at a fixed pace. Throughput = users x per-user rate (the gateway allows 10/s per user, burst 20).
 * <p>
 * Defaults are a smoke run (20 users x 1 order/s for 30 s); milestone u sets the target profile:
 * {@code scripts/load.sh OrderFlowSimulation -Dload.simUsers=1000 -Dload.perUserOrdersPerSec=1 -Dload.duration=300s}
 */
public class OrderFlowSimulation extends Simulation {

    static final int USERS = LoadConfig.getInt("simUsers", 20);
    static final double PER_USER_RATE = LoadConfig.getDouble("perUserOrdersPerSec", 1.0);
    static final Duration DURATION = LoadConfig.getDuration("duration", Duration.ofSeconds(30));
    static final Duration RAMP = LoadConfig.getDuration("rampUp", Duration.ofSeconds(10));

    static final HttpProtocolBuilder PROTOCOL = http.baseUrl(LoadConfig.gatewayUrl())
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("tradewind-load");

    /** One funded user per virtual user (circular if there are more virtual users than load users). */
    static FeederBuilder<String> users() {
        return csv(LoadConfig.usersFile().toAbsolutePath().toString()).circular();
    }

    static final ChainBuilder LOGIN = exec(http("login").post("/api/auth/login")
            .body(StringBody("{\"email\":\"#{email}\",\"password\":\"#{password}\"}"))
            .check(status().is(200), jsonPath("$.accessToken").saveAs("token")));

    /** One market buy; a fresh Idempotency-Key per order. */
    static final ChainBuilder PLACE_ORDER = exec(session -> session
            .set("symbol", LoadConfig.SYMBOLS.get(ThreadLocalRandom.current().nextInt(LoadConfig.SYMBOLS.size())))
            .set("key", UUID.randomUUID().toString()))
            .exec(http("place order").post("/api/orders")
                    .header("Authorization", "Bearer #{token}")
                    .header("Idempotency-Key", "#{key}")
                    .body(StringBody("{\"symbol\":\"#{symbol}\",\"side\":\"BUY\",\"type\":\"MARKET\",\"qty\":\"1\"}"))
                    .check(status().is(202)));

    final ScenarioBuilder orders = scenario("order flow")
            .feed(users())
            .exec(LOGIN)
            .during(DURATION).on(pace(Duration.ofNanos((long) (1_000_000_000L / PER_USER_RATE))).exec(PLACE_ORDER));

    {
        System.out.printf("OrderFlowSimulation: %d users x %.2f orders/s for %s (target %.0f orders/s)%n",
                USERS, PER_USER_RATE, DURATION, USERS * PER_USER_RATE);
        setUp(orders.injectOpen(rampUsers(USERS).during(RAMP)))
                .protocols(PROTOCOL)
                .assertions(global().failedRequests().percent().lt(1.0),
                        details("place order").responseTime().percentile(99.0).gte(0));     // always reported
    }
}
