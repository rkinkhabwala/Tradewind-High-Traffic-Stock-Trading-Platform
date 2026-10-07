package com.tradewind.correctness;

import com.tradewind.common.kafka.KafkaTopics;
import com.tradewind.common.kafka.Topics;
import com.tradewind.common.money.Price;
import com.tradewind.common.quote.QuoteHash;
import com.tradewind.common.quote.QuoteTick;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.common.ConsumerGroupState;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The system under test, started once per JVM and shared by every test class:
 * Postgres (one database per service), Redis and Kafka in containers, and the real service jars as processes:
 * two order-service instances, one execution-service, one portfolio-service. The tests play marketdata-service
 * (Redis quotes, symbol directory, quotes.ticks), which makes prices fully controllable.
 */
final class Cluster {

    static final Instant SIM_START = Instant.parse("2026-01-05T15:00:00Z");   // Monday 10:00 New York

    private static Cluster instance;

    final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15");
    final GenericContainer<?> redis = new GenericContainer<>("redis:7.4.11").withExposedPorts(6379);
    final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.3.1");

    ServiceProcess portfolio;
    ServiceProcess execution;
    List<ServiceProcess> orders;
    JdbcClient ordersDb;
    JdbcClient executionDb;
    JdbcClient portfolioDb;
    Api api;

    static synchronized Cluster get() {
        if (instance == null) {
            instance = new Cluster();
            instance.start();
            Runtime.getRuntime().addShutdownHook(new Thread(instance::stop));
        }
        return instance;
    }

    private void start() {
        postgres.start();
        redis.start();
        kafka.start();
        JdbcClient admin = jdbc(postgres.getJdbcUrl());
        for (String db : List.of("orders", "execution", "portfolio")) {
            admin.sql("CREATE DATABASE " + db).update();
        }
        try {
            KafkaTopics.ensure(kafka.getBootstrapServers(), Topics.ALL, Duration.ofSeconds(30));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        Path root = Path.of(System.getProperty("user.dir")).getParent();
        Path logs = Path.of(System.getProperty("user.dir"), "target.nosync", "service-logs");
        try {
            Files.createDirectories(logs);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Map<String, String> common = new HashMap<>(Map.of(
                "POSTGRES_USER", postgres.getUsername(),
                "POSTGRES_PASSWORD", postgres.getPassword(),
                "REDIS_HOST", redis.getHost(),
                "REDIS_PORT", Integer.toString(redis.getMappedPort(6379)),
                "KAFKA_BOOTSTRAP_SERVERS", kafka.getBootstrapServers(),
                "MARKET_CLOCK_MODE", "FAST",
                "MARKET_CLOCK_SPEED", "1",
                "MARKET_CLOCK_SIM_START", SIM_START.toString(),
                "MARKET_CLOCK_REAL_EPOCH", Instant.now().toString()));

        int portfolioPort = freePort();
        portfolio = service("portfolio-service", root, logs, portfolioPort, common,
                Map.of("PORTFOLIO_SERVICE_PORT", Integer.toString(portfolioPort),
                        "PORTFOLIO_DB_URL", dbUrl("portfolio")));
        int executionPort = freePort();
        execution = service("execution-service", root, logs, executionPort, common,
                Map.of("EXECUTION_SERVICE_PORT", Integer.toString(executionPort),
                        "EXECUTION_DB_URL", dbUrl("execution")));
        orders = List.of(orderService("order-service-1", root, logs, common), orderService("order-service-2", root, logs, common));

        List<ServiceProcess> all = List.of(portfolio, execution, orders.get(0), orders.get(1));
        all.forEach(ServiceProcess::start);
        all.forEach(s -> s.awaitHealthy(Duration.ofSeconds(120)));
        awaitConsumerGroups();

        ordersDb = jdbc(dbUrl("orders"));
        executionDb = jdbc(dbUrl("execution"));
        portfolioDb = jdbc(dbUrl("portfolio"));
        api = new Api(orders, portfolio);
    }

    private ServiceProcess orderService(String name, Path root, Path logs, Map<String, String> common) {
        int port = freePort();
        return service("order-service", name, root, logs, port, common, Map.of(
                "ORDER_SERVICE_PORT", Integer.toString(port),
                "ORDER_DB_URL", dbUrl("orders"),
                "PORTFOLIO_SERVICE_URL", "http://localhost:" + portfolio.port()));
    }

    private ServiceProcess service(String module, Path root, Path logs, int port, Map<String, String> common,
                                   Map<String, String> own) {
        return service(module, module, root, logs, port, common, own);
    }

    private ServiceProcess service(String module, String name, Path root, Path logs, int port,
                                   Map<String, String> common, Map<String, String> own) {
        Map<String, String> env = new HashMap<>(common);
        env.putAll(own);
        Path jar = root.resolve(module).resolve("target.nosync").resolve(module + "-0.1.0-SNAPSHOT.jar");
        return new ServiceProcess(name, jar, port, env, logs);
    }

    /** Kill -9 execution-service; its consumer-group membership times out on the broker. */
    void killExecution() {
        execution.kill();
    }

    void restartExecution() {
        execution.start();
        execution.awaitHealthy(Duration.ofSeconds(120));
        awaitConsumerGroups();
    }

    /** Every consumer group has a member with partitions, so nothing the tests send goes unread. */
    void awaitConsumerGroups() {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            List<String> groups = List.of("execution-service", "portfolio-service", "order-service");
            while (System.nanoTime() < deadline) {
                Map<String, ConsumerGroupDescription> described = admin.describeConsumerGroups(groups).all().get();
                boolean ready = described.values().stream().allMatch(g -> g.state() == ConsumerGroupState.STABLE
                        && g.members().stream().anyMatch(m -> !m.assignment().topicPartitions().isEmpty()));
                if (ready) {
                    return;
                }
                Thread.sleep(500);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Consumer groups not ready", e);
        }
        throw new IllegalStateException("Consumer groups did not stabilise in time");
    }

    /** Plays marketdata-service: symbol directory and latest quote in Redis. */
    void publishQuote(String symbol, String bid, String ask, long size) {
        QuoteTick tick = new QuoteTick(symbol, Price.of(bid), Price.of(ask), Price.of(bid), size, size, 0, Instant.now());
        try (Jedis jedis = new Jedis(redis.getHost(), redis.getMappedPort(6379))) {
            jedis.sadd(QuoteHash.SYMBOLS_KEY, symbol);
            jedis.hset(QuoteHash.key(symbol), QuoteHash.toHash(tick, Price.of(bid)));
        }
    }

    void stop() {
        if (orders != null) {
            orders.forEach(ServiceProcess::stop);
        }
        if (execution != null) {
            execution.stop();
        }
        if (portfolio != null) {
            portfolio.stop();
        }
        kafka.stop();
        redis.stop();
        postgres.stop();
    }

    private String dbUrl(String database) {
        return postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
    }

    private JdbcClient jdbc(String url) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return JdbcClient.create(dataSource);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
