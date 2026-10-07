package com.tradewind.portfolio.position;

import com.tradewind.common.money.Quantity;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PositionRepository {

    private static final RowMapper<Position> MAPPER = (rs, n) -> new Position(rs.getObject("account_id", UUID.class),
            rs.getString("symbol"), Quantity.of(rs.getBigDecimal("qty")), rs.getBigDecimal("avg_cost"));

    private final JdbcClient jdbc;

    PositionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Position> findForUpdate(UUID accountId, String symbol) {
        return jdbc.sql("SELECT * FROM positions_view WHERE account_id = :a AND symbol = :s FOR UPDATE")
                .param("a", accountId).param("s", symbol).query(MAPPER).optional();
    }

    /** Writes the position, or deletes the row once nothing is held. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(Position position, Instant now) {
        if (position.qty().isZero()) {
            jdbc.sql("DELETE FROM positions_view WHERE account_id = :a AND symbol = :s")
                    .param("a", position.accountId()).param("s", position.symbol()).update();
            return;
        }
        jdbc.sql("""
                        INSERT INTO positions_view (account_id, symbol, qty, avg_cost, updated_at)
                        VALUES (:a, :s, :qty, :avg, :now)
                        ON CONFLICT (account_id, symbol) DO UPDATE
                            SET qty = EXCLUDED.qty, avg_cost = EXCLUDED.avg_cost, updated_at = EXCLUDED.updated_at""")
                .param("a", position.accountId()).param("s", position.symbol())
                .param("qty", position.qty().value()).param("avg", position.avgCost())
                .param("now", Timestamp.from(now))
                .update();
    }

    public Optional<Position> find(UUID accountId, String symbol) {
        return jdbc.sql("SELECT * FROM positions_view WHERE account_id = :a AND symbol = :s")
                .param("a", accountId).param("s", symbol).query(MAPPER).optional();
    }

    public List<Position> forAccount(UUID accountId) {
        return jdbc.sql("SELECT * FROM positions_view WHERE account_id = :a ORDER BY symbol")
                .param("a", accountId).query(MAPPER).list();
    }
}
