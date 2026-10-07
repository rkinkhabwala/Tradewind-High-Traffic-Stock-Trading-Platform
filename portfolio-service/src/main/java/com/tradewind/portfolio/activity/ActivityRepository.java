package com.tradewind.portfolio.activity;

import com.tradewind.common.money.Money;
import com.tradewind.common.money.Price;
import com.tradewind.common.money.Quantity;
import com.tradewind.common.order.OrderSide;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class ActivityRepository {

    public enum Type { DEPOSIT, WITHDRAWAL, FILL }

    /** One line of account activity. Fill fields are null for deposits and withdrawals. */
    public record Activity(UUID id, Type type, String referenceId, String symbol, OrderSide side, Quantity qty,
                           Price price, Money amount, Instant occurredAt) {
    }

    public record Cursor(Instant occurredAt, UUID id) {
    }

    private final JdbcClient jdbc;

    ActivityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID accountId, Type type, String referenceId, String symbol, OrderSide side, Quantity qty,
                       Price price, Money signedAmount, Instant at) {
        jdbc.sql("""
                        INSERT INTO activity (id, account_id, type, reference_id, symbol, side, qty, price, amount_cents, occurred_at)
                        VALUES (:id, :account, :type, :ref, :symbol, :side, :qty, :price, :amount, :at)
                        ON CONFLICT (reference_id) DO NOTHING""")
                .param("id", UUID.randomUUID()).param("account", accountId).param("type", type.name())
                .param("ref", referenceId).param("symbol", symbol).param("side", side == null ? null : side.name())
                .param("qty", qty == null ? null : qty.value()).param("price", price == null ? null : price.value())
                .param("amount", signedAmount.cents()).param("at", Timestamp.from(at))
                .update();
    }

    /** Newest first, keyset-paginated on (occurred_at, id). */
    public List<Activity> page(UUID accountId, Cursor after, int limit) {
        return jdbc.sql("""
                        SELECT * FROM activity
                        WHERE account_id = :account
                          AND (CAST(:afterTs AS TIMESTAMPTZ) IS NULL OR (occurred_at, id) < (:afterTs, :afterId))
                        ORDER BY occurred_at DESC, id DESC
                        LIMIT :limit""")
                .param("account", accountId)
                .param("afterTs", after == null ? null : Timestamp.from(after.occurredAt()))
                .param("afterId", after == null ? null : after.id())
                .param("limit", limit)
                .query((rs, n) -> {
                    BigDecimal qty = rs.getBigDecimal("qty");
                    BigDecimal price = rs.getBigDecimal("price");
                    String side = rs.getString("side");
                    return new Activity(rs.getObject("id", UUID.class), Type.valueOf(rs.getString("type")),
                            rs.getString("reference_id"), rs.getString("symbol"),
                            side == null ? null : OrderSide.valueOf(side), qty == null ? null : Quantity.of(qty),
                            price == null ? null : Price.of(price), Money.ofCents(rs.getLong("amount_cents")),
                            rs.getTimestamp("occurred_at").toInstant());
                })
                .list();
    }
}
