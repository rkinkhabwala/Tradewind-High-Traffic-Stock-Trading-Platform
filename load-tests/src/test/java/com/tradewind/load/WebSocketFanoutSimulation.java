package com.tradewind.load;

import io.gatling.http.action.ws.WsInboundMessage;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ThreadLocalRandom;

import static io.gatling.javaapi.core.CoreDsl.doIf;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.regex;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static io.gatling.javaapi.http.HttpDsl.ws;

/**
 * Spec 8 "10,000 concurrent WebSocket clients, each subscribed to 5-20 symbols" and "tick-to-client p99 < 250 ms
 * (conflation included)", optionally with "10,000 ticks/s for a 60 s burst". Each virtual user is one client
 * doing what the web app does: one-time ticket, connect through the gateway, wait for hello, subscribe, ping every
 * 15 s. Once a second it processes the quotes it received: latency = Gatling's receive timestamp minus the quote's
 * {@code publishedAt} (host and Docker clocks; the server-side stream.tick.to.client metric is the cross-check).
 * <pre>
 * scripts/load.sh WebSocketFanoutSimulation -Dload.clients=10000 -Dload.rampUp=120s -Dload.hold=180s \
 *     -Dload.tickBurstMultiplier=3.6 -Dload.tickBurstSeconds=60
 * </pre>
 */
public class WebSocketFanoutSimulation extends Simulation {

    final int clients = LoadConfig.getInt("clients", 100);
    final Duration ramp = LoadConfig.getDuration("rampUp", Duration.ofSeconds(20));
    final Duration hold = LoadConfig.getDuration("hold", Duration.ofSeconds(60));
    final double burstMultiplier = LoadConfig.getDouble("tickBurstMultiplier", 0);
    final long burstSeconds = LoadConfig.getInt("tickBurstSeconds", 60);
    final int minSymbols = LoadConfig.getInt("minSymbols", 5);
    final int maxSymbols = LoadConfig.getInt("maxSymbols", 20);

    /** Wall-clock window of the tick burst (epoch ms), if any; quotes received inside it are recorded apart. */
    static volatile long burstFrom = Long.MAX_VALUE;
    static volatile long burstUntil = Long.MAX_VALUE;
    private long startMillis;
    private final Timer timer = new Timer("tick-burst", true);

    static final HttpProtocolBuilder PROTOCOL = http.baseUrl(LoadConfig.gatewayUrl())
            .wsBaseUrl(LoadConfig.gatewayWsUrl())
            .wsUnmatchedInboundMessageBufferSize(5_000)
            .acceptHeader("application/json")
            .userAgentHeader("tradewind-load")
            .shareConnections();

    String subscribe() {
        List<String> symbols = new ArrayList<>(LoadConfig.SYMBOLS);
        Collections.shuffle(symbols, ThreadLocalRandom.current());
        int n = ThreadLocalRandom.current().nextInt(minSymbols, maxSymbols + 1);
        return "{\"op\":\"subscribe\",\"symbols\":[\"" + String.join("\",\"", symbols.subList(0, n)) + "\"]}";
    }

    /** Records tick-to-client for every buffered quote. Cheap parsing: 10k clients may receive 100k+ quotes/s. */
    static io.gatling.javaapi.core.Session processQuotes(List<WsInboundMessage> messages, io.gatling.javaapi.core.Session session) {
        for (WsInboundMessage message : messages) {
            if (!(message instanceof WsInboundMessage.Text text)) {
                continue;
            }
            String body = text.message();
            int at = body.indexOf("\"publishedAt\":\"");
            if (!body.startsWith("{\"type\":\"quote\"")) {
                continue;
            }
            Recorder.count("quotes received");
            if (at < 0) {
                continue;                                    // subscribe-time snapshot: no publish time
            }
            long published = Instant.parse(body.substring(at + 15, body.indexOf('"', at + 15))).toEpochMilli();
            long received = text.timestamp();
            long micros = (received - published) * 1000;
            Recorder.recordMicros("tick-to-client", micros);
            Recorder.recordMicros(received >= burstFrom && received < burstUntil ? "tick-to-client (tick burst)"
                    : "tick-to-client (steady)", micros);
        }
        return session;
    }

    final ScenarioBuilder scenario = scenario("websocket client")
            .feed(Tokens.randomFeeder())
            .exec(http("ws ticket").post("/api/ws/ticket").header("Authorization", "Bearer #{token}")
                    .check(status().is(200), jsonPath("$.ticket").saveAs("ticket")))
            .exec(ws("connect").connect("/ws?ticket=#{ticket}")
                    .await(Duration.ofSeconds(15)).on(ws.checkTextMessage("hello").check(regex("\"type\":\"hello\""))))
            .exec(ws("subscribe").sendText(session -> subscribe())
                    .await(Duration.ofSeconds(15)).on(ws.checkTextMessage("subscriptions")
                            .check(regex("\"type\":\"subscriptions\"")).silent()))
            .exec(session -> {
                Recorder.count("clients subscribed");
                return session;
            })
            .during(hold, "second").on(
                    pause(Duration.ofSeconds(1))
                            .exec(ws.processUnmatchedMessages(WebSocketFanoutSimulation::processQuotes))
                            .exec(doIf(session -> session.getInt("second") % 15 == 14)
                                    .then(exec(ws("ping").sendText("{\"op\":\"ping\"}")))))
            .exec(ws.processUnmatchedMessages(WebSocketFanoutSimulation::processQuotes))
            .exec(ws("close").close());

    {
        System.out.printf("WebSocketFanoutSimulation: %d clients over %s, each held %s, %d-%d symbols%s%n", clients, ramp,
                hold, minSymbols, maxSymbols, burstMultiplier > 0 ? ", tick burst x" + burstMultiplier + " for "
                        + burstSeconds + " s" : "");
        setUp(scenario.injectOpen(rampUsers(clients).during(ramp)))
                .protocols(PROTOCOL)
                .assertions(global().failedRequests().percent().lt(1.0));
    }

    @Override
    public void before() {
        try {
            Tokens.login();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        startMillis = System.currentTimeMillis();
        if (burstMultiplier > 0) {
            // After everyone connected and a minute of steady state (or the hold's midpoint if shorter).
            long delay = ramp.toMillis() + Math.min(60_000, hold.toMillis() / 2);
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    boost(burstMultiplier, burstSeconds);
                    burstFrom = System.currentTimeMillis() + 2_000;     // ticks published before the boost are still in flight
                    burstUntil = System.currentTimeMillis() + burstSeconds * 1000;
                    System.out.printf("[load] tick burst x%.1f for %d s started%n", burstMultiplier, burstSeconds);
                }
            }, delay);
        }
    }

    static void boost(double multiplier, long seconds) {
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create(LoadConfig.get("marketdataUrl", "http://localhost:8082") + "/internal/simulator/rate"))
                    .header("Content-Type", "application/json").timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"multiplier\":" + multiplier + ",\"durationSeconds\":" + seconds + "}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                System.out.println("[load] tick burst refused: " + r.statusCode() + " " + r.body());
            }
        } catch (Exception e) {
            System.out.println("[load] tick burst failed: " + e);
        }
    }

    @Override
    public void after() {
        timer.cancel();
        long endMillis = System.currentTimeMillis();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("clients", clients);
        settings.put("rampUp", ramp.toString());
        settings.put("hold", hold.toString());
        settings.put("symbolsPerClient", minSymbols + "-" + maxSymbols);
        settings.put("tickBurstMultiplier", burstMultiplier);
        settings.put("tickBurstSeconds", burstMultiplier > 0 ? burstSeconds : 0);
        try {
            Thread.sleep(6000);
            Map<String, Object> server = Prom.snapshot(startMillis, endMillis);
            if (burstUntil != Long.MAX_VALUE) {
                server.put("burstTicksPerSec", Prom.query("sum(increase(marketdata_ticks_produced_total[" + burstSeconds
                        + "s])) / " + burstSeconds, burstUntil));
                server.put("burstTickToClientP99Ms", Prom.query("1000 * histogram_quantile(0.99, sum by (le) (increase("
                        + "stream_tick_to_client_seconds_bucket[" + burstSeconds + "s])))", burstUntil));
            }
            Recorder.report(getClass().getSimpleName(), settings, startMillis, endMillis, server);
        } catch (Exception e) {
            throw new IllegalStateException("report failed", e);
        }
    }
}
