package com.tradewind.correctness;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** A service jar running as its own OS process, so it can really be killed. */
final class ServiceProcess {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

    private final String name;
    private final Path jar;
    private final int port;
    private final Map<String, String> env;
    private final Path log;
    private Process process;

    ServiceProcess(String name, Path jar, int port, Map<String, String> env, Path logDir) {
        this.name = name;
        this.jar = jar;
        this.port = port;
        this.env = env;
        this.log = logDir.resolve(name + ".log");
    }

    String name() {
        return name;
    }

    int port() {
        return port;
    }

    String baseUrl() {
        return "http://localhost:" + port;
    }

    void start() {
        if (!Files.exists(jar)) {
            throw new IllegalStateException(jar + " not found: run `mvn package` (or `mvn verify` from the root) first");
        }
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx320m", "-XX:+ExitOnOutOfMemoryError", "-jar", jar.toString()));
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.environment().putAll(env);
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("Could not start " + name, e);
        }
    }

    void awaitHealthy(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(name + " exited during startup; see " + log);
            }
            try {
                HttpResponse<Void> response = HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/actuator/health"))
                        .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                // not listening yet
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            sleep(Duration.ofMillis(300));
        }
        throw new IllegalStateException(name + " not healthy within " + timeout + "; see " + log);
    }

    /** SIGKILL: no shutdown hooks, no graceful Kafka/DB close. A crash, not a stop. */
    void kill() {
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    boolean isAlive() {
        return process != null && process.isAlive();
    }

    File logFile() {
        return log.toFile();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
