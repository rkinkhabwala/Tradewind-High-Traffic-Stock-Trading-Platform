package com.tradewind.load;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Order-to-fill as a user sees it (spec 8 "end to end"): one WebSocket per load user, through the gateway, the way
 * the web app listens. The time runs from just before the order request is sent to the arrival of the pushed
 * FILLED order event. Fills can arrive before the order's 202 is processed, so both orders of arrival are matched.
 */
final class FillObserver {

    private static final Pattern ORDER_ID = Pattern.compile("\"order\":\\{\"id\":\"([0-9a-f-]{36})\"");
    private record Sent(long nanos, String group) {
    }

    private static final ConcurrentHashMap<String, Sent> SENT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> EARLY_FILLS = new ConcurrentHashMap<>();
    private static final List<WebSocket> SOCKETS = new ArrayList<>();
    private static ScheduledExecutorService pinger;

    private FillObserver() {
    }

    static void start(List<Tokens.User> users) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        Api api = new Api(LoadConfig.gatewayUrl());
        List<Future<WebSocket>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(32)) {
            for (Tokens.User user : users) {
                futures.add(pool.submit(() -> connect(http, api, user)));
            }
        }
        int failed = 0;
        for (Future<WebSocket> f : futures) {
            WebSocket ws = f.get();
            if (ws == null) {
                failed++;
            } else {
                SOCKETS.add(ws);
            }
        }
        Recorder.count("fill observer sockets that could not connect", failed);
        if (failed > users.size() / 10) {
            throw new IllegalStateException(failed + " of " + users.size() + " observer sockets failed to connect");
        }
        pinger = Executors.newSingleThreadScheduledExecutor();
        pinger.scheduleAtFixedRate(() -> SOCKETS.forEach(ws -> ws.sendText("{\"op\":\"ping\"}", true)), 15, 15, TimeUnit.SECONDS);
        System.out.printf("[load] fill observer: %d user sockets open, %d failed%n", SOCKETS.size(), failed);
    }

    /** Ticket + connect with a few retries (the gateway may be struggling: that is what is being measured). */
    private static WebSocket connect(HttpClient http, Api api, Tokens.User user) {
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                Api.Response ticket = api.post("/api/ws/ticket", user.token(), null, false);
                if (ticket.status() == 200) {
                    return http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                            .buildAsync(URI.create(LoadConfig.gatewayWsUrl() + "/ws?ticket=" + ticket.body().get("ticket").asText()),
                                    new Listener())
                            .get(15, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                // retry below
            }
            try {
                Thread.sleep(250L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** Called when an order was accepted (202); {@code sentNanos} = just before its request was sent. */
    static void sent(String orderId, long sentNanos, String group) {
        Long filled = EARLY_FILLS.remove(orderId);
        if (filled != null) {
            record(group, filled - sentNanos);
        } else {
            SENT.put(orderId, new Sent(sentNanos, group));
        }
    }

    private static void filled(String orderId, long nowNanos) {
        Sent sent = SENT.remove(orderId);
        if (sent != null) {
            record(sent.group(), nowNanos - sent.nanos());
        } else {
            EARLY_FILLS.put(orderId, nowNanos);
        }
    }

    private static void record(String group, long nanos) {
        Recorder.recordNanos("order-to-fill (pushed to the user)", nanos);
        if (group != null && !group.isEmpty()) {
            Recorder.recordNanos("order-to-fill " + group, nanos);
        }
        Recorder.count("fills seen by users");
    }

    /** Accepted orders whose fill never reached the user's socket (still resting, or a lost push). */
    static int unmatched() {
        return SENT.size();
    }

    static void stop() {
        if (pinger != null) {
            pinger.shutdownNow();
        }
        SOCKETS.forEach(WebSocket::abort);
        SOCKETS.clear();
    }

    private static final class Listener implements WebSocket.Listener {
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            long now = System.nanoTime();
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                if (text.startsWith("{\"type\":\"order\"") && text.contains("\"status\":\"FILLED\"")) {
                    Matcher m = ORDER_ID.matcher(text);
                    if (m.find()) {
                        filled(m.group(1), now);
                    }
                }
            }
            ws.request(1);
            return null;
        }
    }
}
