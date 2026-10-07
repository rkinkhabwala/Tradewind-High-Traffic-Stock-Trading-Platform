package com.tradewind.portfolio.reconciliation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * On-demand "nightly" reconciliation (spec section 4): re-sums the ledger and compares it with the cached
 * balances. Runs in one REPEATABLE READ snapshot so concurrent postings cannot produce false differences.
 */
@Service
public class ReconciliationService {

    public record BalanceMismatch(UUID ledgerAccountId, BigDecimal cached, BigDecimal ledger) {
    }

    public record UnbalancedTxn(UUID txnId, String unit, BigDecimal sum) {
    }

    public record NegativeBalance(UUID ledgerAccountId, String type, BigDecimal amount) {
    }

    /** positions_view (read model) disagrees with POSITION + POSITION_RESERVED in the ledger. */
    public record PositionMismatch(UUID accountId, String symbol, BigDecimal readModel, BigDecimal ledger) {
    }

    public record Report(Instant checkedAt, long ledgerAccounts, long transactions, long entries,
                         List<BalanceMismatch> balanceMismatches, List<UnbalancedTxn> unbalancedTransactions,
                         List<NegativeBalance> negativeBalances, List<PositionMismatch> positionMismatches,
                         boolean ok) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate snapshot;
    private final Clock clock;

    ReconciliationService(JdbcClient jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.snapshot = new TransactionTemplate(transactionManager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
    }

    public Report run() {
        return snapshot.execute(status -> {
            List<BalanceMismatch> mismatches = jdbc.sql("""
                            SELECT b.ledger_account_id, b.amount AS cached, coalesce(sum(e.amount), 0) AS ledger
                            FROM balances b LEFT JOIN ledger_entries e ON e.ledger_account_id = b.ledger_account_id
                            GROUP BY b.ledger_account_id, b.amount
                            HAVING b.amount <> coalesce(sum(e.amount), 0)""")
                    .query((rs, n) -> new BalanceMismatch(rs.getObject(1, UUID.class),
                            rs.getBigDecimal("cached"), rs.getBigDecimal("ledger")))
                    .list();
            List<UnbalancedTxn> unbalanced = jdbc.sql("""
                            SELECT e.txn_id, coalesce(a.symbol, 'USD') AS unit, sum(e.amount) AS total
                            FROM ledger_entries e JOIN ledger_accounts a ON a.id = e.ledger_account_id
                            GROUP BY e.txn_id, coalesce(a.symbol, 'USD')
                            HAVING sum(e.amount) <> 0""")
                    .query((rs, n) -> new UnbalancedTxn(rs.getObject(1, UUID.class),
                            rs.getString("unit"), rs.getBigDecimal("total")))
                    .list();
            List<NegativeBalance> negative = jdbc.sql("""
                            SELECT b.ledger_account_id, a.type, b.amount
                            FROM balances b JOIN ledger_accounts a ON a.id = b.ledger_account_id
                            WHERE a.type <> 'HOUSE' AND b.amount < 0""")
                    .query((rs, n) -> new NegativeBalance(rs.getObject(1, UUID.class),
                            rs.getString("type"), rs.getBigDecimal("amount")))
                    .list();
            List<PositionMismatch> positions = jdbc.sql("""
                            WITH owned AS (
                                SELECT a.account_id, a.symbol, sum(b.amount) AS qty
                                FROM ledger_accounts a JOIN balances b ON b.ledger_account_id = a.id
                                WHERE a.type IN ('POSITION', 'POSITION_RESERVED')
                                GROUP BY a.account_id, a.symbol)
                            SELECT coalesce(p.account_id, o.account_id) AS account_id,
                                   coalesce(p.symbol, o.symbol) AS symbol,
                                   coalesce(p.qty, 0) AS read_model, coalesce(o.qty, 0) AS ledger
                            FROM positions_view p
                                FULL OUTER JOIN owned o ON o.account_id = p.account_id AND o.symbol = p.symbol
                            WHERE coalesce(p.qty, 0) <> coalesce(o.qty, 0)""")
                    .query((rs, n) -> new PositionMismatch(rs.getObject("account_id", UUID.class),
                            rs.getString("symbol"), rs.getBigDecimal("read_model"), rs.getBigDecimal("ledger")))
                    .list();
            long accounts = count("SELECT count(*) FROM ledger_accounts");
            long txns = count("SELECT count(*) FROM ledger_txns");
            long entries = count("SELECT count(*) FROM ledger_entries");
            boolean ok = mismatches.isEmpty() && unbalanced.isEmpty() && negative.isEmpty() && positions.isEmpty();
            return new Report(clock.instant(), accounts, txns, entries, mismatches, unbalanced, negative, positions, ok);
        });
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
