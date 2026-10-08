package com.tradewind.load;

import io.gatling.javaapi.core.ScenarioBuilder;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.randomSwitch;
import static io.gatling.javaapi.core.CoreDsl.percent;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;

/**
 * Spec 8 "hot symbol": 70% of orders on one symbol without the other symbols' latency degrading more than 2x.
 * Execution is a single writer per symbol (one partition, one thread), so the hot symbol's orders queue behind
 * each other; the question is whether everything else stays fast. API latency and order-to-fill are recorded
 * separately for "hot" and "other"; compare "other" with OrderFlowSimulation at the same total rate.
 * {@code scripts/load.sh HotSymbolSimulation -Dload.ordersPerSec=1000 -Dload.hotSymbol=AAPL}
 */
public class HotSymbolSimulation extends OrderSimulation {

    final double rate = LoadConfig.getDouble("ordersPerSec", 20);
    final double hotShare = LoadConfig.getDouble("hotShare", 70);
    final String hot = LoadConfig.get("hotSymbol", "AAPL");
    final Duration ramp = LoadConfig.getDuration("rampUp", Duration.ofSeconds(30));
    final Duration duration = LoadConfig.getDuration("duration", Duration.ofSeconds(30));
    final List<String> others = LoadConfig.SYMBOLS.stream().filter(s -> !s.equals(hot)).toList();

    final ScenarioBuilder orders = scenario("hot symbol")
            .feed(Tokens.randomFeeder())
            .exec(randomSwitch().on(
                    percent(hotShare).then(placeOrder("place order (hot)", s -> hot, "hot")),
                    percent(100 - hotShare).then(placeOrder("place order (other)",
                            s -> others.get(ThreadLocalRandom.current().nextInt(others.size())), "other"))));

    {
        settings.put("ordersPerSec", rate);
        settings.put("hotSymbol", hot);
        settings.put("hotSharePercent", hotShare);
        settings.put("duration", duration.toString());
        System.out.printf("HotSymbolSimulation: %.0f orders/s, %.0f%% on %s, for %s%n", rate, hotShare, hot, duration);
        setUp(orders.injectOpen(rampUsersPerSec(1).to(rate).during(ramp), constantUsersPerSec(rate).during(duration)))
                .protocols(PROTOCOL)
                .assertions(global().failedRequests().percent().lt(1.0));
    }
}
