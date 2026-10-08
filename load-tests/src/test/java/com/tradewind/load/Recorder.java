package com.tradewind.load;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Client-side measurements of a run (HdrHistogram, microsecond resolution) and counters, written with the
 * server-side view from Prometheus to {@code load-tests/target.nosync/results/<simulation>-<time>.json}.
 */
final class Recorder {

    private static final Map<String, Histogram> HISTOGRAMS = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> COUNTERS = new ConcurrentHashMap<>();

    private Recorder() {
    }

    static void recordMicros(String name, long micros) {
        HISTOGRAMS.computeIfAbsent(name, n -> new ConcurrentHistogram(3_600_000_000L, 3))
                .recordValue(Math.max(0, Math.min(micros, 3_600_000_000L)));
    }

    static void recordNanos(String name, long nanos) {
        recordMicros(name, nanos / 1_000);
    }

    static void count(String name) {
        COUNTERS.computeIfAbsent(name, n -> new LongAdder()).increment();
    }

    static void count(String name, long delta) {
        COUNTERS.computeIfAbsent(name, n -> new LongAdder()).add(delta);
    }

    static long counter(String name) {
        LongAdder adder = COUNTERS.get(name);
        return adder == null ? 0 : adder.sum();
    }

    static Map<String, Object> summary(Histogram h) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("count", h.getTotalCount());
        s.put("p50Ms", ms(h.getValueAtPercentile(50)));
        s.put("p95Ms", ms(h.getValueAtPercentile(95)));
        s.put("p99Ms", ms(h.getValueAtPercentile(99)));
        s.put("p999Ms", ms(h.getValueAtPercentile(99.9)));
        s.put("maxMs", ms(h.getMaxValue()));
        return s;
    }

    private static double ms(long micros) {
        return Math.round(micros / 100.0) / 10.0;
    }

    /** Writes and prints the run report. {@code server} comes from {@link Prom#snapshot}. */
    static Path report(String simulation, Map<String, Object> settings, long startMillis, long endMillis,
                       Map<String, Object> server) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("simulation", simulation);
        report.put("start", Instant.ofEpochMilli(startMillis).toString());
        report.put("end", Instant.ofEpochMilli(endMillis).toString());
        report.put("seconds", (endMillis - startMillis) / 1000);
        report.put("settings", settings);
        Map<String, Object> client = new TreeMap<>();
        HISTOGRAMS.forEach((name, h) -> client.put(name, summary(h)));
        report.put("clientLatency", client);
        Map<String, Long> counters = new TreeMap<>();
        COUNTERS.forEach((name, adder) -> counters.put(name, adder.sum()));
        report.put("counters", counters);
        report.put("server", server);

        Path dir = LoadConfig.repoRoot().resolve("load-tests/target.nosync/results");
        Files.createDirectories(dir);
        Path file = dir.resolve(simulation + "-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(startMillis)) + ".json");
        Files.writeString(file, Api.JSON.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(report));

        System.out.println("\n[load] ==== " + simulation + " (" + report.get("seconds") + " s) ====");
        client.forEach((name, s) -> System.out.println("[load] client " + name + ": " + s));
        counters.forEach((name, n) -> System.out.println("[load] count " + name + ": " + n));
        server.forEach((name, v) -> System.out.println("[load] server " + name + ": " + v));
        System.out.println("[load] report: " + file);
        return file;
    }
}
