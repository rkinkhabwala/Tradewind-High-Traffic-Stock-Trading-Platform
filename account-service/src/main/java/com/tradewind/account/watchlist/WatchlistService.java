package com.tradewind.account.watchlist;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Watchlists (spec 7). Adding or removing a symbol is naturally idempotent; creating a list is made idempotent by
 * the Idempotency-Key in the controller. Symbols are checked for shape only: the list is a user's bookmarks, and
 * quotes for an unknown symbol simply never arrive.
 */
@Service
public class WatchlistService {

    public static final int MAX_LISTS_PER_ACCOUNT = 20;
    public static final int MAX_SYMBOLS_PER_LIST = 100;

    public record Watchlist(UUID id, String name, List<String> symbols, Instant createdAt) {
    }

    /** A business rule refused the change (422 with this code). */
    public static class WatchlistRuleException extends RuntimeException {
        private final String code;

        public WatchlistRuleException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static class WatchlistNotFoundException extends RuntimeException {
        public WatchlistNotFoundException(UUID id) {
            super("Watchlist " + id + " not found");
        }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    WatchlistService(JdbcClient jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    public List<Watchlist> list(UUID accountId) {
        record Row(UUID id, String name, Instant createdAt, String symbol) {
        }
        List<Row> rows = jdbc.sql("""
                        SELECT w.id, w.name, w.created_at, s.symbol
                        FROM watchlists w LEFT JOIN watchlist_symbols s ON s.watchlist_id = w.id
                        WHERE w.account_id = :account
                        ORDER BY w.created_at, w.id, s.added_at, s.symbol""")
                .param("account", accountId)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("symbol")))
                .list();
        Map<UUID, Watchlist> lists = new LinkedHashMap<>();
        for (Row row : rows) {
            Watchlist list = lists.computeIfAbsent(row.id(), id -> new Watchlist(id, row.name(), new ArrayList<>(), row.createdAt()));
            if (row.symbol() != null) {
                list.symbols().add(row.symbol());
            }
        }
        return lists.values().stream().map(w -> new Watchlist(w.id(), w.name(), List.copyOf(w.symbols()), w.createdAt())).toList();
    }

    public Watchlist create(UUID accountId, String name) {
        return transactions.execute(status -> {
            long existing = jdbc.sql("SELECT count(*) FROM watchlists WHERE account_id = :a").param("a", accountId)
                    .query(Long.class).single();
            if (existing >= MAX_LISTS_PER_ACCOUNT) {
                throw new WatchlistRuleException("TOO_MANY_WATCHLISTS", "At most " + MAX_LISTS_PER_ACCOUNT + " watchlists");
            }
            UUID id = UUID.randomUUID();
            int created = jdbc.sql("""
                            INSERT INTO watchlists (id, account_id, name, created_at) VALUES (:id, :a, :name, :now)
                            ON CONFLICT ON CONSTRAINT watchlists_name_unique DO NOTHING""")
                    .param("id", id).param("a", accountId).param("name", name).param("now", Timestamp.from(clock.instant()))
                    .update();
            if (created == 0) {
                throw new WatchlistRuleException("NAME_TAKEN", "A watchlist named '" + name + "' already exists");
            }
            return find(accountId, id).orElseThrow();
        });
    }

    public Watchlist addSymbol(UUID accountId, UUID watchlistId, String symbol) {
        return transactions.execute(status -> {
            requireOwned(accountId, watchlistId);
            long count = jdbc.sql("SELECT count(*) FROM watchlist_symbols WHERE watchlist_id = :w").param("w", watchlistId)
                    .query(Long.class).single();
            boolean present = jdbc.sql("SELECT count(*) FROM watchlist_symbols WHERE watchlist_id = :w AND symbol = :s")
                    .param("w", watchlistId).param("s", symbol).query(Long.class).single() > 0;
            if (!present && count >= MAX_SYMBOLS_PER_LIST) {
                throw new WatchlistRuleException("WATCHLIST_FULL", "At most " + MAX_SYMBOLS_PER_LIST + " symbols per watchlist");
            }
            jdbc.sql("""
                            INSERT INTO watchlist_symbols (watchlist_id, symbol, added_at) VALUES (:w, :s, :now)
                            ON CONFLICT DO NOTHING""")
                    .param("w", watchlistId).param("s", symbol).param("now", Timestamp.from(clock.instant()))
                    .update();
            return find(accountId, watchlistId).orElseThrow();
        });
    }

    public Watchlist removeSymbol(UUID accountId, UUID watchlistId, String symbol) {
        return transactions.execute(status -> {
            requireOwned(accountId, watchlistId);
            jdbc.sql("DELETE FROM watchlist_symbols WHERE watchlist_id = :w AND symbol = :s")
                    .param("w", watchlistId).param("s", symbol).update();
            return find(accountId, watchlistId).orElseThrow();
        });
    }

    private void requireOwned(UUID accountId, UUID watchlistId) {
        // Locks the list row, so concurrent adds cannot both pass the size check.
        boolean owned = jdbc.sql("SELECT id FROM watchlists WHERE id = :w AND account_id = :a FOR UPDATE")
                .param("w", watchlistId).param("a", accountId).query(UUID.class).optional().isPresent();
        if (!owned) {
            throw new WatchlistNotFoundException(watchlistId);
        }
    }

    private Optional<Watchlist> find(UUID accountId, UUID watchlistId) {
        return list(accountId).stream().filter(w -> w.id().equals(watchlistId)).findFirst();
    }
}
