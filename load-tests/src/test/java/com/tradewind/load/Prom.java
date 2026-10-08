package com.tradewind.load;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** The server-side view of a run window, from Prometheus (same series as the Grafana dashboards). */
final class Prom {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private Prom() {
    }

    /** Scalar result of an instant query at {@code atMillis}; NaN if empty. Multiple series are returned as a map. */
    static Object query(String expr, long atMillis) {
        try {
            String url = LoadConfig.get("prometheusUrl", "http://localhost:9090") + "/api/v1/query?query="
                    + URLEncoder.encode(expr, StandardCharsets.UTF_8) + "&time=" + atMillis / 1000.0;
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode result = Api.JSON.readTree(r.body()).path("data").path("result");
            if (result.size() == 0) {
                return Double.NaN;
            }
            if (result.size() == 1) {
                return round(result.get(0).path("value").get(1).asDouble());
            }
            Map<String, Double> series = new LinkedHashMap<>();
            for (JsonNode s : result) {
                series.put(s.path("metric").toString(), round(s.path("value").get(1).asDouble()));
            }
            return series;
        } catch (Exception e) {
            return "error: " + e;
        }
    }

    private static double round(double v) {
        return Double.isFinite(v) ? Math.round(v * 1000) / 1000.0 : v;
    }

    private static String quantileMs(double q, String bucket, String selector, String window) {
        return "1000 * histogram_quantile(" + q + ", sum by (le) (increase(" + bucket + "{" + selector + "}[" + window + "])))";
    }

    /** Key server metrics over [start, end] (Prometheus scrapes every 5 s). */
    static Map<String, Object> snapshot(long startMillis, long endMillis) {
        long seconds = Math.max(10, (endMillis - startMillis) / 1000);
        String w = seconds + "s";
        long at = endMillis;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gatewayPlaceOrderP50Ms", query(quantileMs(0.5, "spring_cloud_gateway_requests_seconds_bucket", "routeId=\"orders-place\"", w), at));
        m.put("gatewayPlaceOrderP99Ms", query(quantileMs(0.99, "spring_cloud_gateway_requests_seconds_bucket", "routeId=\"orders-place\"", w), at));
        m.put("orderServicePlaceOrderP99Ms", query(quantileMs(0.99, "http_server_requests_seconds_bucket",
                "application=\"order-service\",method=\"POST\",uri=\"/api/orders\"", w), at));
        m.put("reservationCallP99Ms", query(quantileMs(0.99, "orders_reservation_duration_seconds_bucket", "", w), at));
        m.put("orderToFillMarketP50Ms", query(quantileMs(0.5, "orders_fill_latency_seconds_bucket", "type=\"MARKET\"", w), at));
        m.put("orderToFillMarketP99Ms", query(quantileMs(0.99, "orders_fill_latency_seconds_bucket", "type=\"MARKET\"", w), at));
        m.put("ordersAcceptedPerSec", query("sum(increase(orders_status_total{status=\"ACCEPTED\"}[" + w + "])) / " + seconds, at));
        m.put("ordersRejectedPerSec", query("sum(increase(orders_status_total{status=\"REJECTED\"}[" + w + "])) / " + seconds, at));
        m.put("fillsPerSec", query("sum(increase(execution_fills_total[" + w + "])) / " + seconds, at));
        m.put("gateway429s", query("sum(increase(spring_cloud_gateway_requests_seconds_count{routeId=\"orders-place\",httpStatusCode=\"429\"}[" + w + "]))", at));
        m.put("http5xx", query("sum by (application) (increase(http_server_requests_seconds_count{status=~\"5..\"}[" + w + "]))", at));
        m.put("ledgerConflicts", query("sum(increase(ledger_conflicts_total[" + w + "]))", at));
        m.put("ticksProducedPerSec", query("sum(increase(marketdata_ticks_produced_total[" + w + "])) / " + seconds, at));
        m.put("ticksPerSecMax", query("max_over_time(sum(rate(marketdata_ticks_produced_total[15s]))[" + w + ":5s])", at));
        m.put("tickToClientP50Ms", query(quantileMs(0.5, "stream_tick_to_client_seconds_bucket", "", w), at));
        m.put("tickToClientP99Ms", query(quantileMs(0.99, "stream_tick_to_client_seconds_bucket", "", w), at));
        m.put("maxWebSocketConnections", query("max_over_time(sum(stream_sessions)[" + w + ":5s])", at));
        m.put("slowClientCloses", query("sum(increase(stream_sessions_closed_total{reason=\"slow\"}[" + w + "]))", at));
        m.put("maxConsumerLag", query("max by (consumergroup) (max_over_time(kafka_consumergroup_lag[" + w + "]))", at));
        m.put("maxDbPoolPending", query("max by (application) (max_over_time(hikaricp_connections_pending[" + w + "]))", at));
        m.put("maxProcessCpu", query("max by (application) (max_over_time(process_cpu_usage[" + w + "]))", at));
        return m;
    }
}
