package com.tradewind.portfolio.reservation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
class ReservationRepository {

    private final JdbcClient jdbc;

    ReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns false if a row for this order already exists (concurrent or repeated reserve, or a tombstone). */
    boolean insertOpen(UUID orderId, UUID accountId, String asset, BigDecimal amount, Instant now) {
        return jdbc.sql("""
                        INSERT INTO reservations
                            (order_id, account_id, asset, reserved_amount, remaining_amount, status, created_at, updated_at)
                        VALUES (:order, :account, :asset, :amount, :amount, 'OPEN', :now, :now)
                        ON CONFLICT (order_id) DO NOTHING""")
                .param("order", orderId).param("account", accountId).param("asset", asset)
                .param("amount", amount).param("now", Timestamp.from(now))
                .update() == 1;
    }

    boolean insertTombstone(UUID orderId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO reservations
                            (order_id, account_id, asset, reserved_amount, remaining_amount, status, created_at, updated_at)
                        VALUES (:order, NULL, NULL, 0, 0, 'CLOSED', :now, :now)
                        ON CONFLICT (order_id) DO NOTHING""")
                .param("order", orderId).param("now", Timestamp.from(now))
                .update() == 1;
    }

    Optional<Reservation> find(UUID orderId) {
        return query("SELECT * FROM reservations WHERE order_id = :order", orderId);
    }

    /** Row lock: serializes release (and, later, fills) for one order. */
    Optional<Reservation> findForUpdate(UUID orderId) {
        return query("SELECT * FROM reservations WHERE order_id = :order FOR UPDATE", orderId);
    }

    void close(UUID orderId, Instant now) {
        jdbc.sql("UPDATE reservations SET remaining_amount = 0, status = 'CLOSED', updated_at = :now WHERE order_id = :order")
                .param("order", orderId).param("now", Timestamp.from(now))
                .update();
    }

    private Optional<Reservation> query(String sql, UUID orderId) {
        return jdbc.sql(sql).param("order", orderId)
                .query((rs, n) -> new Reservation(
                        rs.getObject("order_id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        rs.getString("asset"),
                        rs.getBigDecimal("reserved_amount"),
                        rs.getBigDecimal("remaining_amount"),
                        Reservation.Status.valueOf(rs.getString("status"))))
                .optional();
    }
}
