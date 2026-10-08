package com.tradewind.load;

import io.gatling.javaapi.core.ScenarioBuilder;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;

/**
 * Spec 8 "market-open burst": 5x the normal order rate for 2 minutes, without errors other than the intended
 * 429s. Normal rate, then a step to 5x, then back to normal. Orders come from random load users, so a burst
 * pushes some users past the gateway's 10/s (burst 20) per-user limit: those get 429 with Retry-After.
 * Anything other than 202/429 fails the run.
 * {@code scripts/load.sh MarketOpenBurstSimulation -Dload.ordersPerSec=1000}
 */
public class MarketOpenBurstSimulation extends OrderSimulation {

    final double rate = LoadConfig.getDouble("ordersPerSec", 20);
    final double factor = LoadConfig.getDouble("burstFactor", 5);
    final Duration normal = LoadConfig.getDuration("normal", Duration.ofSeconds(60));
    final Duration burst = LoadConfig.getDuration("burst", Duration.ofSeconds(120));

    final ScenarioBuilder orders = scenario("market open")
            .feed(Tokens.randomFeeder())
            .exec(placeOrder("place order",
                    s -> LoadConfig.SYMBOLS.get(ThreadLocalRandom.current().nextInt(LoadConfig.SYMBOLS.size())), ""));

    {
        settings.put("normalOrdersPerSec", rate);
        settings.put("burstOrdersPerSec", rate * factor);
        settings.put("normal", normal.toString());
        settings.put("burst", burst.toString());
        System.out.printf("MarketOpenBurstSimulation: %.0f/s for %s, %.0f/s for %s, %.0f/s for %s%n",
                rate, normal, rate * factor, burst, rate, normal);
        setUp(orders.injectOpen(
                rampUsersPerSec(1).to(rate).during(Duration.ofSeconds(15)),
                constantUsersPerSec(rate).during(normal),
                rampUsersPerSec(rate).to(rate * factor).during(Duration.ofSeconds(5)),     // the opening bell
                constantUsersPerSec(rate * factor).during(burst),
                constantUsersPerSec(rate).during(normal)))
                .protocols(PROTOCOL)
                .assertions(global().failedRequests().count().is(0L));
    }
}
