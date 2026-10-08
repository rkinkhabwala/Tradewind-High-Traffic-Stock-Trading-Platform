package com.tradewind.stream.ws;

import com.tradewind.stream.TestInfrastructure;
import com.tradewind.stream.session.ClientSession;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Over real sockets: a client that stops reading is closed with 4000 while a healthy client on the same symbols
 * keeps streaming; a silent client is closed with 4001. Timeouts are shortened (slow 2s, idle 3s) and conflation
 * is 10ms so the slow client's buffers fill quickly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "tradewind.stream.conflation-interval=10ms",
        "tradewind.stream.slow-client-timeout=2s",
        "tradewind.stream.idle-timeout=3s",
        "tradewind.stream.close-grace=5s"})
@Import(TestInfrastructure.class)
class SlowAndIdleClientTest {

    static final int SYMBOLS = 100;

    @LocalServerPort
    int port;
    @Autowired
    TestInfrastructure.Ticks ticks;
    @Autowired
    MeterRegistry meters;
    @Autowired
    @Qualifier("ticks")
    HealthIndicator tickConsumer;

    static String symbol(int i) {
        return "S%03d".formatted(i);
    }

    double closedSlow() {
        return meters.get("stream.sessions.closed").tag("reason", "slow").counter().count();
    }

    @Test
    void aClientThatStopsReadingIsClosedWith4000AndOthersAreUnaffected() throws Exception {
        await().atMost(Duration.ofSeconds(30)).until(() -> tickConsumer.health().getStatus().equals(Status.UP));
        ScheduledExecutorService pingers = Executors.newScheduledThreadPool(2);
        AtomicBoolean producing = new AtomicBoolean(true);
        Thread producer = null;
        try (RawWebSocket slow = new RawWebSocket(port, UUID.randomUUID(), UUID.randomUUID());
             WsClient healthy = WsClient.connect(port)) {
            for (int batch = 0; batch < SYMBOLS / 10; batch++) {
                List<String> names = new ArrayList<>();
                for (int i = batch * 10; i < batch * 10 + 10; i++) {
                    names.add("\"" + symbol(i) + "\"");
                }
                slow.sendText("{\"op\":\"subscribe\",\"symbols\":[" + String.join(",", names) + "]}");
            }
            healthy.send("{\"op\":\"subscribe\",\"symbols\":[\"S000\",\"S001\"]}");
            healthy.await("subscriptions");
            // Both keep pinging: the slow one is slow, not idle.
            pingers.scheduleAtFixedRate(() -> {
                try {
                    slow.sendText("{\"op\":\"ping\"}");
                } catch (Exception ignored) {
                    // closed
                }
            }, 0, 500, TimeUnit.MILLISECONDS);
            pingers.scheduleAtFixedRate(() -> healthy.socket.sendText("{\"op\":\"ping\"}", true), 0, 500, TimeUnit.MILLISECONDS);

            double before = closedSlow();
            producer = Thread.ofPlatform().start(() -> {
                for (int round = 0; producing.get(); round++) {
                    for (int i = 0; i < SYMBOLS; i++) {
                        ticks.send(symbol(i), "%d.%02d".formatted(100 + round % 50, i));
                    }
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });

            await().atMost(Duration.ofSeconds(30)).until(() -> closedSlow() > before);
            long[] frames = new long[1];
            assertThat(slow.readUntilClose(frames)).isEqualTo(ClientSession.CLOSE_SLOW);
            assertThat(frames[0]).isPositive();

            healthy.messages.clear();
            healthy.await(WsClient.quoteFor("S000"), Duration.ofSeconds(2));
            healthy.await(WsClient.quoteFor("S001"), Duration.ofSeconds(2));
            assertThat(healthy.closeCode).isNotDone();
            assertThat(closedSlow()).isEqualTo(before + 1);
        } finally {
            producing.set(false);
            if (producer != null) {
                producer.join();
            }
            pingers.shutdownNow();
        }
    }

    @Test
    void aSilentClientIsClosedWith4001AndAPingingOneIsNot() throws Exception {
        ScheduledExecutorService pinger = Executors.newSingleThreadScheduledExecutor();
        try (WsClient silent = WsClient.connect(port); WsClient pinging = WsClient.connect(port)) {
            pinger.scheduleAtFixedRate(() -> pinging.socket.sendText("{\"op\":\"ping\"}", true), 0, 1, TimeUnit.SECONDS);
            assertThat(silent.closeCode.get(8, TimeUnit.SECONDS)).isEqualTo(ClientSession.CLOSE_IDLE);
            Thread.sleep(1500);
            assertThat(pinging.closeCode).isNotDone();
        } finally {
            pinger.shutdownNow();
        }
    }
}
