package com.tradewind.load;

import io.gatling.javaapi.core.ScenarioBuilder;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;

/**
 * Spec 8 "order placement throughput" (1,000 orders/s sustained), "order API latency" (p99 < 100 ms) and
 * "order-to-fill" (p99 < 500 ms): market buys of random liquid symbols at a constant rate.
 * {@code scripts/load.sh OrderFlowSimulation -Dload.ordersPerSec=1000 -Dload.duration=300s}
 */
public class OrderFlowSimulation extends OrderSimulation {

    final double rate = LoadConfig.getDouble("ordersPerSec", 20);
    final Duration ramp = LoadConfig.getDuration("rampUp", Duration.ofSeconds(30));
    final Duration duration = LoadConfig.getDuration("duration", Duration.ofSeconds(30));

    final ScenarioBuilder orders = scenario("order flow")
            .feed(Tokens.randomFeeder())
            .exec(placeOrder("place order",
                    s -> LoadConfig.SYMBOLS.get(ThreadLocalRandom.current().nextInt(LoadConfig.SYMBOLS.size())), ""));

    {
        settings.put("ordersPerSec", rate);
        settings.put("rampUp", ramp.toString());
        settings.put("duration", duration.toString());
        System.out.printf("OrderFlowSimulation: ramp to %.0f orders/s over %s, then %s%n", rate, ramp, duration);
        setUp(orders.injectOpen(rampUsersPerSec(1).to(rate).during(ramp), constantUsersPerSec(rate).during(duration)))
                .protocols(PROTOCOL)
                .assertions(global().failedRequests().percent().lt(1.0));
    }
}
