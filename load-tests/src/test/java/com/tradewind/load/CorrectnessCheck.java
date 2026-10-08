package com.tradewind.load;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * "Correctness under load" (spec 8): run after every load run. Waits until the system is quiet (outboxes drained,
 * no consumer lag on the order/execution topics, no PENDING orders, no reservation release still being retried), then cross-checks the three services' books:
 * <ul>
 *   <li><b>0 lost orders:</b> every order that got past PENDING is known to execution-service, resting or closed,
 *   and agrees on its outcome; rejected orders never reached it.</li>
 *   <li><b>0 duplicate fills:</b> executions map one-to-one onto portfolio FILL ledger transactions, and each
 *   order's filled quantity equals the sum of its executions in both services.</li>
 *   <li><b>No stranded money:</b> no open reservation for a finished order.</li>
 *   <li><b>Ledger:</b> portfolio-service reconciliation reports ok (balanced, no mismatches, nothing negative).</li>
 * </ul>
 * {@code mvn -pl load-tests exec:java -Dexec.mainClass=com.tradewind.load.CorrectnessCheck}
 */
public final class CorrectnessCheck {

    public record Result(boolean ok, Map<String, Object> facts, List<String> problems) {
    }

    private static final Duration QUIET_TIMEOUT = Duration.ofMinutes(10);
    private static final Set<String> TERMINAL = Set.of("FILLED", "CANCELED", "EXPIRED", "REJECTED");
    private static final Set<String> OPEN = Set.of("ACCEPTED", "PARTIALLY_FILLED");

    public static void main(String[] args) throws Exception {
        Result result = run();
        System.exit(result.ok() ? 0 : 1);
    }

    public static Result run() throws Exception {
        long start = System.nanoTime();
        try (Connection orders = connect("orders"); Connection execution = connect("execution");
             Connection portfolio = connect("portfolio")) {
            Map<String, Object> facts = new HashMap<>();
            List<String> problems = new ArrayList<>();
            try {
                facts.put("quietAfterSeconds", awaitQuiet(orders, execution, portfolio).toSeconds());
            } catch (IllegalStateException notQuiet) {
                // Still check everything: the books must be consistent even if something is stuck.
                problems.add(notQuiet.getMessage());
            }
            checkOrders(orders, execution, facts, problems);
            checkFills(execution, portfolio, facts, problems);
            checkReservations(orders, portfolio, facts, problems);
            checkReconciliation(facts, problems);
            Result result = new Result(problems.isEmpty(), facts, problems);
            System.out.printf("%n[correctness] %s in %ds: %s%n", result.ok() ? "OK" : "FAILED",
                    (System.nanoTime() - start) / 1_000_000_000L, facts);
            problems.forEach(p -> System.out.println("[correctness] PROBLEM: " + p));
            return result;
        }
    }

    // ---- quiescence ---------------------------------------------------------------------------------------------

    private static Duration awaitQuiet(Connection orders, Connection execution, Connection portfolio) throws Exception {
        long start = System.nanoTime();
        long deadline = start + QUIET_TIMEOUT.toNanos();
        String last = "";
        while (System.nanoTime() < deadline) {
            long outbox = count(orders, "SELECT count(*) FROM outbox WHERE published_at IS NULL")
                    + count(execution, "SELECT count(*) FROM outbox WHERE published_at IS NULL")
                    + count(portfolio, "SELECT count(*) FROM outbox WHERE published_at IS NULL");
            long pending = count(orders, "SELECT count(*) FROM orders WHERE status = 'PENDING'");
            // Rejected-after-timeout orders whose reservation release is still being retried (order-service sweeper).
            long releasing = count(orders, "SELECT count(*) FROM orders WHERE release_pending");
            long lag = consumerLag();
            if (outbox == 0 && pending == 0 && releasing == 0 && lag == 0) {
                return Duration.ofNanos(System.nanoTime() - start);
            }
            last = "outbox=" + outbox + " pending=" + pending + " releasing=" + releasing + " lag=" + lag;
            Thread.sleep(1000);
        }
        throw new IllegalStateException("system not quiet after " + QUIET_TIMEOUT + ": " + last);
    }

    /** Total lag of every consumer group on every topic except quotes.ticks (ticks never stop). */
    private static long consumerLag() throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, LoadConfig.kafkaBootstrap(),
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000, AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000))) {
            long lag = 0;
            for (ConsumerGroupListing group : admin.listConsumerGroups().all().get(10, TimeUnit.SECONDS)) {
                Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(group.groupId())
                        .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
                Map<TopicPartition, OffsetSpec> request = committed.keySet().stream()
                        .filter(tp -> !tp.topic().equals("quotes.ticks"))
                        .collect(Collectors.toMap(Function.identity(), tp -> OffsetSpec.latest()));
                if (request.isEmpty()) {
                    continue;
                }
                Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends =
                        admin.listOffsets(request).all().get(10, TimeUnit.SECONDS);
                for (var end : ends.entrySet()) {
                    OffsetAndMetadata offset = committed.get(end.getKey());
                    lag += Math.max(0, end.getValue().offset() - (offset == null ? 0 : offset.offset()));
                }
            }
            return lag;
        }
    }

    // ---- orders vs execution ------------------------------------------------------------------------------------

    private record OrderRow(String status, BigDecimal filled, int lastSeq) {
    }

    private static void checkOrders(Connection orders, Connection execution, Map<String, Object> facts,
                                    List<String> problems) throws SQLException {
        Map<UUID, OrderRow> all = new HashMap<>();
        query(orders, "SELECT id, status, filled_qty, last_exec_seq FROM orders", rs ->
                all.put(rs.getObject(1, UUID.class), new OrderRow(rs.getString(2), rs.getBigDecimal(3), rs.getInt(4))));
        Set<UUID> resting = new HashSet<>();
        query(execution, "SELECT order_id FROM resting_orders", rs -> resting.add(rs.getObject(1, UUID.class)));
        Map<UUID, String> closed = new HashMap<>();
        query(execution, "SELECT order_id, outcome FROM closed_orders", rs -> closed.put(rs.getObject(1, UUID.class), rs.getString(2)));
        Map<UUID, BigDecimal> executed = new HashMap<>();
        Map<UUID, Integer> maxSeq = new HashMap<>();
        query(execution, "SELECT order_id, sum(qty), max(seq), count(*) FROM executions GROUP BY order_id", rs -> {
            executed.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2));
            maxSeq.put(rs.getObject(1, UUID.class), rs.getInt(3));
        });

        Map<String, Long> byStatus = all.values().stream().collect(Collectors.groupingBy(OrderRow::status, Collectors.counting()));
        facts.put("orders", all.size());
        facts.put("ordersByStatus", byStatus);
        int lost = 0;
        int disagree = 0;
        int qtyMismatch = 0;
        for (var entry : all.entrySet()) {
            UUID id = entry.getKey();
            OrderRow order = entry.getValue();
            boolean known = resting.contains(id) || closed.containsKey(id);
            if (order.status().equals("REJECTED")) {
                if (known || executed.containsKey(id)) {
                    disagree++;
                    report(problems, disagree, "rejected order " + id + " reached execution-service");
                }
                continue;
            }
            if (!known) {
                lost++;
                report(problems, lost, "order " + id + " is " + order.status() + " but unknown to execution-service (lost)");
                continue;
            }
            String expected = OPEN.contains(order.status()) ? "RESTING" : order.status();
            String actual = resting.contains(id) ? "RESTING" : closed.get(id);
            if (!expected.equals(actual)) {
                disagree++;
                report(problems, disagree, "order " + id + ": order-service " + order.status() + ", execution-service " + actual);
            }
            BigDecimal sum = executed.getOrDefault(id, BigDecimal.ZERO);
            if (sum.compareTo(order.filled()) != 0 || maxSeq.getOrDefault(id, 0) != order.lastSeq()) {
                qtyMismatch++;
                report(problems, qtyMismatch, "order " + id + ": filled " + order.filled() + " (seq " + order.lastSeq()
                        + ") vs executions " + sum + " (seq " + maxSeq.getOrDefault(id, 0) + ")");
            }
        }
        for (UUID id : executed.keySet()) {
            if (!all.containsKey(id)) {
                lost++;
                report(problems, lost, "executions for order " + id + " that order-service does not have");
            }
        }
        facts.put("lostOrders", lost);
        facts.put("statusDisagreements", disagree);
        facts.put("filledQtyMismatches", qtyMismatch);
        facts.put("openOrders", all.values().stream().filter(o -> !TERMINAL.contains(o.status())).count());
    }

    // ---- execution vs ledger ------------------------------------------------------------------------------------

    private static void checkFills(Connection execution, Connection portfolio, Map<String, Object> facts,
                                   List<String> problems) throws SQLException {
        Set<String> executionIds = new HashSet<>();
        query(execution, "SELECT id FROM executions", rs -> executionIds.add("fill:" + rs.getObject(1, UUID.class)));
        Map<String, Integer> fills = new HashMap<>();
        query(portfolio, "SELECT reference_id FROM ledger_txns WHERE type = 'FILL'", rs -> fills.merge(rs.getString(1), 1, Integer::sum));
        long duplicates = fills.values().stream().filter(n -> n > 1).count();
        Set<String> missing = new HashSet<>(executionIds);
        missing.removeAll(fills.keySet());
        Set<String> unknown = new HashSet<>(fills.keySet());
        unknown.removeAll(executionIds);
        facts.put("executions", executionIds.size());
        facts.put("ledgerFills", fills.size());
        facts.put("duplicateFills", duplicates);
        facts.put("fillsMissingInLedger", missing.size());
        facts.put("ledgerFillsWithoutExecution", unknown.size());
        if (duplicates > 0) {
            problems.add(duplicates + " executions were booked more than once");
        }
        missing.stream().limit(10).forEach(f -> problems.add("execution " + f + " has no ledger FILL"));
        unknown.stream().limit(10).forEach(f -> problems.add("ledger " + f + " has no execution"));
    }

    // ---- reservations -------------------------------------------------------------------------------------------

    private static void checkReservations(Connection orders, Connection portfolio, Map<String, Object> facts,
                                          List<String> problems) throws SQLException {
        Map<UUID, String> status = new HashMap<>();
        query(orders, "SELECT id, status FROM orders", rs -> status.put(rs.getObject(1, UUID.class), rs.getString(2)));
        List<UUID> stranded = new ArrayList<>();
        query(portfolio, "SELECT order_id FROM reservations WHERE status = 'OPEN'", rs -> {
            UUID id = rs.getObject(1, UUID.class);
            String s = status.get(id);
            if (s == null || TERMINAL.contains(s)) {
                stranded.add(id);
            }
        });
        facts.put("strandedReservations", stranded.size());
        stranded.stream().limit(10).forEach(id -> problems.add("open reservation for finished or unknown order " + id
                + " (" + status.get(id) + ")"));
    }

    // ---- ledger -------------------------------------------------------------------------------------------------

    private static void checkReconciliation(Map<String, Object> facts, List<String> problems) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create(LoadConfig.portfolioInternalUrl() + "/internal/reconciliation"))
                .timeout(Duration.ofSeconds(120)).GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonNode report = Api.JSON.readTree(response.body());
        facts.put("reconciliationOk", report.path("ok").asBoolean());
        facts.put("ledgerTransactions", report.path("transactions").asLong(report.path("transactionCount").asLong()));
        if (!report.path("ok").asBoolean()) {
            problems.add("reconciliation not ok: " + response.body());
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private interface RowHandler {
        void accept(ResultSet rs) throws SQLException;
    }

    private static void query(Connection connection, String sql, RowHandler handler) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setFetchSize(10_000);
            connection.setAutoCommit(false);             // needed for a streaming (cursor) fetch in Postgres
            try (ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    handler.accept(rs);
                }
            }
            connection.commit();
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        long[] n = {0};
        query(connection, sql, rs -> n[0] = rs.getLong(1));
        return n[0];
    }

    private static void report(List<String> problems, int nth, String problem) {
        if (nth <= 10) {
            problems.add(problem);
        }
    }

    private static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(LoadConfig.jdbcUrl(database), LoadConfig.postgresUser(),
                LoadConfig.postgresPassword());
    }

    private CorrectnessCheck() {
    }
}
