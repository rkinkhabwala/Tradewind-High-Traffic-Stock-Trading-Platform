package com.tradewind.execution.engine;

import com.tradewind.common.money.Price;
import com.tradewind.common.money.Quantity;
import com.tradewind.common.order.OrderSide;
import com.tradewind.common.order.OrderType;
import com.tradewind.common.order.TimeInForce;
import com.tradewind.execution.book.RestingOrder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class ExecutionRepository {

    enum Outcome { FILLED, CANCELED, EXPIRED }

    private final JdbcClient jdbc;

    ExecutionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent consumer: false if this event was already processed (in a committed transaction). */
    boolean markProcessed(UUID eventId, Instant now) {
        return jdbc.sql("INSERT INTO processed_events (event_id, processed_at) VALUES (:id, :now) ON CONFLICT DO NOTHING")
                .param("id", eventId).param("now", Timestamp.from(now))
                .update() == 1;
    }

    void insertResting(RestingOrder o) {
        jdbc.sql("""
                        INSERT INTO resting_orders (order_id, account_id, symbol, side, type, time_in_force, limit_price,
                            price_cap, quantity, remaining_qty, filled_qty, next_seq, session_date, kafka_partition, created_at)
                        VALUES (:id, :account, :symbol, :side, :type, :tif, :limit, :cap, :qty, :remaining, :filled,
                            :seq, :session, :partition, :created)""")
                .param("id", o.orderId()).param("account", o.accountId()).param("symbol", o.symbol())
                .param("side", o.side().name()).param("type", o.type().name()).param("tif", o.timeInForce().name())
                .param("limit", o.limitPrice() == null ? null : o.limitPrice().value())
                .param("cap", o.priceCap() == null ? null : o.priceCap().value())
                .param("qty", o.quantity().value()).param("remaining", o.remaining().value())
                .param("filled", o.filled().value()).param("seq", o.nextSeq())
                .param("session", Date.valueOf(o.sessionDate())).param("partition", o.partition())
                .param("created", Timestamp.from(o.createdAt()))
                .update();
    }

    void updateResting(RestingOrder o) {
        jdbc.sql("UPDATE resting_orders SET remaining_qty = :remaining, filled_qty = :filled, next_seq = :seq WHERE order_id = :id")
                .param("remaining", o.remaining().value()).param("filled", o.filled().value())
                .param("seq", o.nextSeq()).param("id", o.orderId())
                .update();
    }

    void close(UUID orderId, Outcome outcome, Instant now) {
        jdbc.sql("DELETE FROM resting_orders WHERE order_id = :id").param("id", orderId).update();
        jdbc.sql("INSERT INTO closed_orders (order_id, outcome, closed_at) VALUES (:id, :outcome, :now)")
                .param("id", orderId).param("outcome", outcome.name()).param("now", Timestamp.from(now))
                .update();
    }

    Optional<Outcome> outcomeOf(UUID orderId) {
        return jdbc.sql("SELECT outcome FROM closed_orders WHERE order_id = :id").param("id", orderId)
                .query(String.class).optional().map(Outcome::valueOf);
    }

    void insertExecution(UUID id, RestingOrder order, int seq, Quantity qty, Price price, Instant executedAt) {
        jdbc.sql("""
                        INSERT INTO executions (id, order_id, seq, account_id, symbol, side, qty, price, executed_at)
                        VALUES (:id, :order, :seq, :account, :symbol, :side, :qty, :price, :at)""")
                .param("id", id).param("order", order.orderId()).param("seq", seq)
                .param("account", order.accountId()).param("symbol", order.symbol()).param("side", order.side().name())
                .param("qty", qty.value()).param("price", price.value()).param("at", Timestamp.from(executedAt))
                .update();
    }

    List<RestingOrder> restingOn(Collection<Integer> partitions) {
        if (partitions.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM resting_orders WHERE kafka_partition IN (:partitions)")
                .param("partitions", partitions)
                .query((rs, n) -> {
                    BigDecimal limit = rs.getBigDecimal("limit_price");
                    BigDecimal cap = rs.getBigDecimal("price_cap");
                    return new RestingOrder(rs.getObject("order_id", UUID.class), rs.getObject("account_id", UUID.class),
                            rs.getString("symbol"), OrderSide.valueOf(rs.getString("side")),
                            OrderType.valueOf(rs.getString("type")), TimeInForce.valueOf(rs.getString("time_in_force")),
                            limit == null ? null : Price.of(limit), cap == null ? null : Price.of(cap),
                            Quantity.of(rs.getBigDecimal("quantity")), Quantity.of(rs.getBigDecimal("remaining_qty")),
                            Quantity.of(rs.getBigDecimal("filled_qty")), rs.getInt("next_seq"),
                            rs.getDate("session_date").toLocalDate(), rs.getInt("kafka_partition"),
                            rs.getTimestamp("created_at").toInstant());
                })
                .list();
    }

    int purgeProcessedBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM processed_events WHERE processed_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff)).update();
    }
}
