package com.tradewind.portfolio.funding;

import com.tradewind.common.money.Money;
import com.tradewind.portfolio.activity.ActivityRepository;
import com.tradewind.portfolio.events.PortfolioEvents;
import com.tradewind.portfolio.ledger.LedgerAccountKey;
import com.tradewind.portfolio.ledger.LedgerAmounts;
import com.tradewind.portfolio.ledger.LedgerExceptions.ConflictingReferenceException;
import com.tradewind.portfolio.ledger.LedgerService;
import com.tradewind.portfolio.ledger.LedgerTransaction;
import com.tradewind.portfolio.ledger.LedgerTransactions;
import com.tradewind.portfolio.ledger.LedgerTxnType;
import com.tradewind.portfolio.ledger.Posting;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Simulated deposits and withdrawals: cash moves between the house and the account. */
@Service
public class FundingService {

    public record FundingResult(UUID referenceId, Money cashBalance) {
    }

    private final LedgerService ledger;
    private final LedgerTransactions transactions;
    private final PortfolioEvents events;
    private final ActivityRepository activity;
    private final Clock clock;

    FundingService(LedgerService ledger, LedgerTransactions transactions, PortfolioEvents events,
                   ActivityRepository activity, Clock clock) {
        this.ledger = ledger;
        this.transactions = transactions;
        this.events = events;
        this.activity = activity;
        this.clock = clock;
    }

    public FundingResult deposit(UUID referenceId, UUID accountId, Money amount) {
        return move(LedgerTxnType.DEPOSIT, "deposit:" + referenceId, referenceId, accountId,
                LedgerAccountKey.houseCash(), LedgerAccountKey.cash(accountId), amount);
    }

    /** Throws InsufficientBalanceException if available (unreserved) cash is too low. */
    public FundingResult withdraw(UUID referenceId, UUID accountId, Money amount) {
        return move(LedgerTxnType.WITHDRAWAL, "withdrawal:" + referenceId, referenceId, accountId,
                LedgerAccountKey.cash(accountId), LedgerAccountKey.houseCash(), amount);
    }

    private FundingResult move(LedgerTxnType type, String reference, UUID referenceId, UUID accountId,
                               LedgerAccountKey from, LedgerAccountKey to, Money amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive");
        }
        LedgerTransaction txn = LedgerTransaction.transfer(type, reference, from, to, LedgerAmounts.of(amount));
        return transactions.execute(() -> {
            if (ledger.post(txn).applied()) {
                activity.record(accountId, ActivityRepository.Type.valueOf(type.name()), reference, null, null, null,
                        null, type == LedgerTxnType.DEPOSIT ? amount : amount.negate(), clock.instant());
                events.updated(accountId, type.name(), reference, null);
            } else {
                List<Posting> original = ledger.postingsOf(reference).orElseThrow();
                if (!new HashSet<>(original).equals(new HashSet<>(txn.postings()))) {
                    throw new ConflictingReferenceException("Reference " + referenceId + " was used for a different movement");
                }
            }
            return new FundingResult(referenceId,
                    LedgerAmounts.toMoney(ledger.balanceOf(LedgerAccountKey.cash(accountId))));
        });
    }
}
