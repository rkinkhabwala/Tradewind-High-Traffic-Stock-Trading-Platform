package com.tradewind.portfolio.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Plain SQL for the money path: every statement is explicit and reviewable. */
@Repository
class LedgerRepository {

    record BalanceRow(UUID ledgerAccountId, BigDecimal amount, long version) {
    }

    record EntryRow(LedgerAccountKey account, BigDecimal amount) {
    }

    private final JdbcClient jdbc;

    LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns false if a transaction with this reference already exists. */
    boolean insertTxn(UUID id, LedgerTxnType type, String referenceId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO ledger_txns (id, type, reference_id, created_at) VALUES (:id, :type, :ref, :now)
                        ON CONFLICT (reference_id) DO NOTHING""")
                .param("id", id).param("type", type.name()).param("ref", referenceId).param("now", Timestamp.from(now))
                .update() == 1;
    }

    Optional<UUID> findTxnId(String referenceId) {
        return jdbc.sql("SELECT id FROM ledger_txns WHERE reference_id = :ref")
                .param("ref", referenceId).query(UUID.class).optional();
    }

    List<EntryRow> entriesOf(UUID txnId) {
        return jdbc.sql("""
                        SELECT a.account_id, a.type, a.symbol, e.amount
                        FROM ledger_entries e JOIN ledger_accounts a ON a.id = e.ledger_account_id
                        WHERE e.txn_id = :txn""")
                .param("txn", txnId)
                .query((rs, n) -> new EntryRow(new LedgerAccountKey(rs.getObject("account_id", UUID.class),
                        LedgerAccountType.valueOf(rs.getString("type")), rs.getString("symbol")),
                        rs.getBigDecimal("amount")))
                .list();
    }

    Optional<UUID> findAccountId(LedgerAccountKey key) {
        return jdbc.sql("""
                        SELECT id FROM ledger_accounts
                        WHERE account_id = :account AND type = :type AND symbol IS NOT DISTINCT FROM CAST(:symbol AS VARCHAR)""")
                .param("account", key.accountId()).param("type", key.type().name()).param("symbol", key.symbol())
                .query(UUID.class).optional();
    }

    /** Creates the ledger account and its zero balance unless a concurrent transaction already did. */
    void createAccount(UUID id, LedgerAccountKey key, Instant now) {
        int created = jdbc.sql("""
                        INSERT INTO ledger_accounts (id, account_id, type, symbol, created_at)
                        VALUES (:id, :account, :type, :symbol, :now)
                        ON CONFLICT ON CONSTRAINT ledger_accounts_unique DO NOTHING""")
                .param("id", id).param("account", key.accountId()).param("type", key.type().name())
                .param("symbol", key.symbol()).param("now", Timestamp.from(now))
                .update();
        if (created == 1) {
            jdbc.sql("INSERT INTO balances (ledger_account_id, amount, version, allow_negative) VALUES (:id, 0, 0, :neg)")
                    .param("id", id).param("neg", key.allowNegative())
                    .update();
        }
    }

    List<BalanceRow> balances(Collection<UUID> ledgerAccountIds) {
        return jdbc.sql("SELECT ledger_account_id, amount, version FROM balances WHERE ledger_account_id IN (:ids)")
                .param("ids", ledgerAccountIds)
                .query((rs, n) -> new BalanceRow(rs.getObject("ledger_account_id", UUID.class),
                        rs.getBigDecimal("amount"), rs.getLong("version")))
                .list();
    }

    /** Compare-and-set on version: 0 rows means someone else updated the balance first. */
    int updateBalance(UUID ledgerAccountId, BigDecimal newAmount, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE balances SET amount = :amount, version = version + 1
                        WHERE ledger_account_id = :id AND version = :version""")
                .param("amount", newAmount).param("id", ledgerAccountId).param("version", expectedVersion)
                .update();
    }

    /**
     * For changes that cannot break the no-negative rule (credits, house postings): an atomic increment under the
     * row lock instead of read-then-compare-and-set. It bumps the version, so a concurrent guarded debit on the
     * same row still detects the change. The house row is touched by every deposit and fill; optimistic retries
     * on it would collapse under concurrency.
     */
    void incrementBalance(UUID ledgerAccountId, BigDecimal delta) {
        jdbc.sql("UPDATE balances SET amount = amount + :delta, version = version + 1 WHERE ledger_account_id = :id")
                .param("delta", delta).param("id", ledgerAccountId)
                .update();
    }

    void insertEntry(UUID txnId, UUID ledgerAccountId, BigDecimal amount, Instant now) {
        jdbc.sql("""
                        INSERT INTO ledger_entries (id, txn_id, ledger_account_id, amount, created_at)
                        VALUES (:id, :txn, :account, :amount, :now)""")
                .param("id", UUID.randomUUID()).param("txn", txnId).param("account", ledgerAccountId)
                .param("amount", amount).param("now", Timestamp.from(now))
                .update();
    }

    record AccountBalance(LedgerAccountType type, String symbol, BigDecimal amount) {
    }

    List<AccountBalance> balancesOfAccount(UUID accountId) {
        return jdbc.sql("""
                        SELECT a.type, a.symbol, b.amount
                        FROM ledger_accounts a JOIN balances b ON b.ledger_account_id = a.id
                        WHERE a.account_id = :account
                        ORDER BY a.type, a.symbol""")
                .param("account", accountId)
                .query((rs, n) -> new AccountBalance(LedgerAccountType.valueOf(rs.getString("type")),
                        rs.getString("symbol"), rs.getBigDecimal("amount")))
                .list();
    }
}
