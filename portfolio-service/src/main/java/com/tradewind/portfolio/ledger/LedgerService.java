package com.tradewind.portfolio.ledger;

import com.tradewind.portfolio.ledger.LedgerExceptions.InsufficientBalanceException;
import com.tradewind.portfolio.ledger.LedgerExceptions.OptimisticConflictException;
import com.tradewind.portfolio.ledger.LedgerRepository.AccountBalance;
import com.tradewind.portfolio.ledger.LedgerRepository.BalanceRow;
import com.tradewind.portfolio.ledger.LedgerRepository.EntryRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Posts balanced transactions and keeps cached balances in step, in the caller's transaction.
 * Callers run inside {@link LedgerTransactions#execute}, which retries on optimistic-lock conflicts.
 */
@Service
public class LedgerService {

    public record PostingResult(UUID txnId, boolean applied) {
    }

    private final LedgerRepository repository;
    private final Clock clock;

    LedgerService(LedgerRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Writes the transaction once. If its reference was already posted, nothing changes and
     * {@code applied == false}. Throws {@link InsufficientBalanceException} or
     * {@link OptimisticConflictException}; either way the caller's transaction must roll back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostingResult post(LedgerTransaction txn) {
        Instant now = clock.instant();
        UUID txnId = UUID.randomUUID();
        if (!repository.insertTxn(txnId, txn.type(), txn.referenceId(), now)) {
            return new PostingResult(repository.findTxnId(txn.referenceId()).orElseThrow(), false);
        }

        Map<LedgerAccountKey, UUID> ids = new LinkedHashMap<>();
        for (Posting posting : txn.postings()) {
            ids.computeIfAbsent(posting.account(), key -> resolveAccount(key, now));
        }

        // Net change per ledger account, applied in id order so concurrent transactions lock rows consistently.
        Map<UUID, BigDecimal> deltas = new TreeMap<>(Comparator.naturalOrder());
        Map<UUID, LedgerAccountKey> keysById = new LinkedHashMap<>();
        for (Posting posting : txn.postings()) {
            UUID id = ids.get(posting.account());
            deltas.merge(id, posting.amount(), BigDecimal::add);
            keysById.put(id, posting.account());
        }
        // Only debits of non-house accounts can break "never negative"; those use optimistic locking.
        // Credits (and house postings) cannot, so they are atomic increments that never need a retry.
        List<UUID> guarded = deltas.entrySet().stream()
                .filter(d -> needsGuard(keysById.get(d.getKey()), d.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        Map<UUID, BalanceRow> current = guarded.isEmpty() ? Map.of() : repository.balances(guarded).stream()
                .collect(Collectors.toMap(BalanceRow::ledgerAccountId, Function.identity()));

        for (Map.Entry<UUID, BigDecimal> delta : deltas.entrySet()) {
            LedgerAccountKey key = keysById.get(delta.getKey());
            if (!needsGuard(key, delta.getValue())) {
                repository.incrementBalance(delta.getKey(), delta.getValue());
                continue;
            }
            BalanceRow row = current.get(delta.getKey());
            BigDecimal updated = row.amount().add(delta.getValue());
            if (updated.signum() < 0) {
                throw new InsufficientBalanceException(key);
            }
            if (repository.updateBalance(row.ledgerAccountId(), updated, row.version()) != 1) {
                throw new OptimisticConflictException();
            }
        }
        for (Posting posting : txn.postings()) {
            repository.insertEntry(txnId, ids.get(posting.account()), posting.amount(), now);
        }
        return new PostingResult(txnId, true);
    }

    /** The postings of an already-posted reference, to check a replay matches the original. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<List<Posting>> postingsOf(String referenceId) {
        return repository.findTxnId(referenceId).map(id -> repository.entriesOf(id).stream()
                .map((EntryRow e) -> new Posting(e.account(), e.amount()))
                .toList());
    }

    @Transactional(readOnly = true)
    public List<AccountBalance> balancesOf(UUID accountId) {
        return repository.balancesOfAccount(accountId);
    }

    /** Current balance of one ledger account; zero if it was never used. */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal balanceOf(LedgerAccountKey key) {
        return repository.findAccountId(key)
                .flatMap(id -> repository.balances(List.of(id)).stream().findFirst())
                .map(BalanceRow::amount)
                .orElse(BigDecimal.ZERO);
    }

    private static boolean needsGuard(LedgerAccountKey key, BigDecimal delta) {
        return !key.allowNegative() && delta.signum() < 0;
    }

    private UUID resolveAccount(LedgerAccountKey key, Instant now) {
        return repository.findAccountId(key).orElseGet(() -> {
            repository.createAccount(UUID.randomUUID(), key, now);
            return repository.findAccountId(key).orElseThrow();
        });
    }
}
