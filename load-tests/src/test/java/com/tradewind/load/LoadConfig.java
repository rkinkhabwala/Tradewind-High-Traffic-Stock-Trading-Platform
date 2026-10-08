package com.tradewind.load;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings for load runs: system property ({@code -Dload.users=1000}), else environment variable
 * ({@code LOAD_USERS}), else the repository's {@code .env} (for the Postgres credentials), else a default.
 * Everything targets the compose stack on localhost.
 */
public final class LoadConfig {

    private static final Map<String, String> DOT_ENV = readDotEnv();

    private LoadConfig() {
    }

    public static String get(String key, String fallback) {
        String property = System.getProperty("load." + key);
        if (property != null && !property.isBlank()) {
            return property;
        }
        String envName = key.replaceAll("([a-z])([A-Z])", "$1_$2").replace('.', '_').replace('-', '_').toUpperCase();
        String env = System.getenv(envName);
        if (env != null && !env.isBlank()) {
            return env;
        }
        return DOT_ENV.getOrDefault(envName, fallback);
    }

    public static int getInt(String key, int fallback) {
        return Integer.parseInt(get(key, Integer.toString(fallback)));
    }

    public static double getDouble(String key, double fallback) {
        return Double.parseDouble(get(key, Double.toString(fallback)));
    }

    public static Duration getDuration(String key, Duration fallback) {
        String value = get(key, null);
        return value == null ? fallback : Duration.parse(value.startsWith("P") ? value : "PT" + value.toUpperCase());
    }

    /** The gateway: load goes through the public API, rate limits and all. */
    public static String gatewayUrl() {
        return get("gatewayUrl", "http://localhost:8080");
    }

    public static String gatewayWsUrl() {
        return gatewayUrl().replaceFirst("^http", "ws");
    }

    public static String portfolioInternalUrl() {
        return get("portfolioUrl", "http://localhost:8085");
    }

    public static String kafkaBootstrap() {
        return get("kafkaBootstrapServers", "localhost:9092");
    }

    public static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + get("postgresHost", "localhost") + ":" + get("postgresPort", "5432") + "/"
                + database + "?connectTimeout=5&socketTimeout=120";
    }

    public static String postgresUser() {
        return get("postgresUser", "tradewind");
    }

    public static String postgresPassword() {
        return get("postgresPassword", "");
    }

    public static int userCount() {
        return getInt("users", 1000);
    }

    public static String userEmail(int index) {
        return "%s-%04d@tradewind.test".formatted(get("userPrefix", "load"), index);
    }

    public static String userPassword() {
        return get("userPassword", "load-test-password");
    }

    /** In load-tests/work.nosync (survives mvn clean), wherever Maven was started from (exec:java and Gatling differ). */
    public static Path usersFile() {
        String configured = get("usersFile", null);
        return configured != null ? Path.of(configured)
                : repoRoot().resolve("load-tests").resolve("work.nosync").resolve("load-users.csv");
    }

    /** The repository root: the nearest directory, from here upwards, that contains load-tests/. */
    public static Path repoRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("load-tests"))) {
                return dir;
            }
        }
        throw new IllegalStateException("run from inside the Tradewind repository");
    }

    /** Liquid, well-known symbols from the simulator's catalog (scripts/generate_symbols.py). */
    public static final List<String> SYMBOLS = List.of("AAPL", "MSFT", "GOOGL", "AMZN", "NVDA", "META", "TSLA", "JPM",
            "V", "JNJ", "WMT", "PG", "MA", "UNH", "HD", "XOM", "CVX", "KO", "PEP", "COST", "DIS", "NFLX", "ADBE", "CRM",
            "INTC", "AMD", "ORCL", "CSCO", "PFE", "NKE", "MCD", "BA", "GE", "IBM", "T", "VZ", "GME", "AMC", "PLTR",
            "UBER", "SBUX", "NEE");

    private static Map<String, String> readDotEnv() {
        Map<String, String> values = new HashMap<>();
        for (Path candidate : List.of(Path.of(".env"), Path.of("..", ".env"), Path.of("..", "..", ".env"))) {
            if (Files.isRegularFile(candidate)) {
                try {
                    for (String line : Files.readAllLines(candidate)) {
                        int eq = line.indexOf('=');
                        if (!line.isBlank() && !line.startsWith("#") && eq > 0) {
                            values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                        }
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("cannot read " + candidate, e);
                }
                break;
            }
        }
        return values;
    }
}
