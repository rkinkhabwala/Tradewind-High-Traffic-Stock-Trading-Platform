package com.tradewind.portfolio.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A balanced set of postings. Construction fails unless, for every unit, the postings sum to exactly zero.
 * {@code referenceId} is unique in the ledger, which makes posting the same business event twice a no-op.
 */
public record LedgerTransaction(LedgerTxnType type, String referenceId, List<Posting> postings) {

    public LedgerTransaction {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(referenceId, "referenceId");
        postings = List.copyOf(postings);
        if (postings.size() < 2) {
            throw new IllegalArgumentException("a transaction needs at least two postings");
        }
        Map<String, BigDecimal> sumPerUnit = postings.stream().collect(Collectors.groupingBy(
                p -> p.account().unit(), Collectors.reducing(BigDecimal.ZERO, Posting::amount, BigDecimal::add)));
        sumPerUnit.forEach((unit, sum) -> {
            if (sum.signum() != 0) {
                throw new IllegalArgumentException("unbalanced transaction " + referenceId + ": " + unit + " sums to " + sum);
            }
        });
    }

    /** Moves {@code amount} from one account to another of the same unit. */
    public static LedgerTransaction transfer(LedgerTxnType type, String referenceId,
                                             LedgerAccountKey from, LedgerAccountKey to, BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("transfer amount must be positive: " + amount);
        }
        return new LedgerTransaction(type, referenceId, List.of(new Posting(from, amount.negate()), new Posting(to, amount)));
    }
}
