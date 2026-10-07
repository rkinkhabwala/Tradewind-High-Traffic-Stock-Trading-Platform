package com.tradewind.order.order;

import com.tradewind.common.money.Price;
import com.tradewind.common.money.Quantity;
import com.tradewind.common.order.OrderSide;
import com.tradewind.common.order.OrderType;
import com.tradewind.common.order.TimeInForce;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** All status changes are compare-and-set on the current status, after checking the state machine allows them. */
@Repository
class OrderRepository {

    private static final RowMapper<Order> MAPPER = (rs, n) -> {
        BigDecimal limit = rs.getBigDecimal("limit_price");
        BigDecimal avg = rs.getBigDecimal("avg_fill_price");
        BigDecimal cap = rs.getBigDecimal("price_cap");
        Timestamp cancel = rs.getTimestamp("cancel_requested_at");
        Long cash = rs.getObject("reserved_cash_cents", Long.class);
        return new Order(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getString("client_order_id"), rs.getString("symbol"),
                OrderSide.valueOf(rs.getString("side")), OrderType.valueOf(rs.getString("type")),
                TimeInForce.valueOf(rs.getString("time_in_force")), Quantity.of(rs.getBigDecimal("qty")),
                limit == null ? null : Price.of(limit), OrderStatus.valueOf(rs.getString("status")),
                Quantity.of(rs.getBigDecimal("filled_qty")), avg == null ? null : Price.of(avg),
                rs.getString("reject_reason"), cash, cap == null ? null : Price.of(cap),
                cancel == null ? null : cancel.toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    };

    record Cursor(Instant createdAt, UUID id) {
    }

    private final JdbcClient jdbc;

    OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns false if this (account, client order id) already exists. */
    boolean insertPending(Order o) {
        return jdbc.sql("""
                        INSERT INTO orders (id, account_id, client_order_id, symbol, side, type, time_in_force, qty,
                                            limit_price, status, reserved_cash_cents, price_cap, created_at, updated_at)
                        VALUES (:id, :account, :client, :symbol, :side, :type, :tif, :qty, :limit, 'PENDING',
                                :cash, :cap, :now, :now)
                        ON CONFLICT ON CONSTRAINT orders_client_order_id_unique DO NOTHING""")
                .param("id", o.id()).param("account", o.accountId()).param("client", o.clientOrderId())
                .param("symbol", o.symbol()).param("side", o.side().name()).param("type", o.type().name())
                .param("tif", o.timeInForce().name()).param("qty", o.quantity().value())
                .param("limit", o.limitPrice() == null ? null : o.limitPrice().value())
                .param("cash", o.reservedCashCents())
                .param("cap", o.priceCap() == null ? null : o.priceCap().value())
                .param("now", Timestamp.from(o.createdAt()))
                .update() == 1;
    }

    Optional<Order> find(UUID id) {
        return jdbc.sql("SELECT * FROM orders WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    Optional<Order> findByClientOrderId(UUID accountId, String clientOrderId) {
        return jdbc.sql("SELECT * FROM orders WHERE account_id = :account AND client_order_id = :client")
                .param("account", accountId).param("client", clientOrderId).query(MAPPER).optional();
    }

    /** Compare-and-set status change. Returns 1 if this caller made the transition. */
    int transition(UUID id, OrderStatus from, OrderStatus to, String rejectReason, boolean releasePending, Instant now) {
        OrderStatus.requireTransition(from, to);
        return jdbc.sql("""
                        UPDATE orders SET status = :to, reject_reason = :reason, release_pending = :release,
                                          updated_at = :now
                        WHERE id = :id AND status = :from""")
                .param("to", to.name()).param("reason", rejectReason).param("release", releasePending)
                .param("now", Timestamp.from(now)).param("id", id).param("from", from.name())
                .update();
    }

    /** Marks the first cancel request on an open order. Returns 1 only for the first request. */
    int requestCancel(UUID id, Instant now) {
        return jdbc.sql("""
                        UPDATE orders SET cancel_requested_at = :now, updated_at = :now
                        WHERE id = :id AND status IN ('ACCEPTED', 'PARTIALLY_FILLED') AND cancel_requested_at IS NULL""")
                .param("now", Timestamp.from(now)).param("id", id)
                .update();
    }

    List<UUID> stalePending(Instant createdBefore, int limit) {
        return jdbc.sql("SELECT id FROM orders WHERE status = 'PENDING' AND created_at < :before ORDER BY created_at LIMIT :limit")
                .param("before", Timestamp.from(createdBefore)).param("limit", limit)
                .query(UUID.class).list();
    }

    List<UUID> awaitingRelease(int limit) {
        return jdbc.sql("SELECT id FROM orders WHERE release_pending ORDER BY id LIMIT :limit")
                .param("limit", limit).query(UUID.class).list();
    }

    /** What a fill needs to know about the order, locked for the update. */
    record FillState(OrderStatus status, Quantity filled, BigDecimal fillNotional, int lastExecSeq) {
    }

    Optional<FillState> lockFillState(UUID id) {
        return jdbc.sql("SELECT status, filled_qty, fill_notional, last_exec_seq FROM orders WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query((rs, n) -> new FillState(OrderStatus.valueOf(rs.getString("status")),
                        Quantity.of(rs.getBigDecimal("filled_qty")), rs.getBigDecimal("fill_notional"),
                        rs.getInt("last_exec_seq")))
                .optional();
    }

    /** Applies one execution: filled quantity, average price, seq and the (state-machine checked) status. */
    void applyFill(UUID id, OrderStatus from, OrderStatus to, Quantity filled, Price avgFillPrice,
                   BigDecimal fillNotional, int seq, Instant now) {
        OrderStatus.requireTransition(from, to);
        int updated = jdbc.sql("""
                        UPDATE orders SET status = :to, filled_qty = :filled, avg_fill_price = :avg,
                                          fill_notional = :notional, last_exec_seq = :seq, updated_at = :now
                        WHERE id = :id AND status = :from""")
                .param("to", to.name()).param("filled", filled.value()).param("avg", avgFillPrice.value())
                .param("notional", fillNotional).param("seq", seq).param("now", Timestamp.from(now))
                .param("id", id).param("from", from.name())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Order " + id + " changed while applying a fill");
        }
    }

    boolean markProcessed(UUID eventId, Instant now) {
        return jdbc.sql("INSERT INTO processed_events (event_id, processed_at) VALUES (:id, :now) ON CONFLICT DO NOTHING")
                .param("id", eventId).param("now", Timestamp.from(now))
                .update() == 1;
    }

    void clearReleasePending(UUID id) {
        jdbc.sql("UPDATE orders SET release_pending = FALSE WHERE id = :id").param("id", id).update();
    }

    /** Newest first; keyset pagination on (created_at, id) so pages stay stable as new orders arrive. */
    List<Order> page(UUID accountId, OrderStatus status, Cursor after, int limit) {
        return jdbc.sql("""
                        SELECT * FROM orders
                        WHERE account_id = :account
                          AND (CAST(:status AS VARCHAR) IS NULL OR status = :status)
                          AND (CAST(:afterTs AS TIMESTAMPTZ) IS NULL OR (created_at, id) < (:afterTs, :afterId))
                        ORDER BY created_at DESC, id DESC
                        LIMIT :limit""")
                .param("account", accountId)
                .param("status", status == null ? null : status.name())
                .param("afterTs", after == null ? null : Timestamp.from(after.createdAt()))
                .param("afterId", after == null ? null : after.id())
                .param("limit", limit)
                .query(MAPPER).list();
    }
}
