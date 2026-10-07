package com.tradewind.portfolio.reservation;

import com.tradewind.portfolio.ledger.LedgerAccountKey;
import com.tradewind.portfolio.ledger.LedgerExceptions.ConflictingReferenceException;
import com.tradewind.portfolio.ledger.LedgerService;
import com.tradewind.portfolio.ledger.LedgerTransaction;
import com.tradewind.portfolio.ledger.LedgerTransactions;
import com.tradewind.portfolio.ledger.LedgerTxnType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

/**
 * Reserve/release API used synchronously by order-service (spec 6.3 step 3). Both operations are idempotent
 * per order id, so order-service can retry after a timeout without double-reserving.
 */
@Service
public class ReservationService {

    private final ReservationRepository reservations;
    private final LedgerService ledger;
    private final LedgerTransactions transactions;
    private final Clock clock;

    ReservationService(ReservationRepository reservations, LedgerService ledger, LedgerTransactions transactions,
                       Clock clock) {
        this.reservations = reservations;
        this.ledger = ledger;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Moves {@code amount} of {@code asset} ("USD" or a symbol) from available to reserved.
     * Throws InsufficientBalanceException if not enough is available (nothing is recorded, so it may be retried),
     * or ConflictingReferenceException if this order id was already used differently or already released.
     */
    public Reservation reserve(UUID orderId, UUID accountId, String asset, BigDecimal amount) {
        return transactions.execute(() -> {
            if (!reservations.insertOpen(orderId, accountId, asset, amount, clock.instant())) {
                Reservation existing = reservations.find(orderId).orElseThrow();
                if (existing.isTombstone()) {
                    throw new ConflictingReferenceException("Order " + orderId + " was already released");
                }
                if (!matches(existing, accountId, asset, amount)) {
                    throw new ConflictingReferenceException("Order " + orderId + " was reserved with different terms");
                }
                return existing;
            }
            ledger.post(LedgerTransaction.transfer(LedgerTxnType.RESERVE, "reserve:" + orderId,
                    available(accountId, asset), reserved(accountId, asset), amount));
            return reservations.find(orderId).orElseThrow();
        });
    }

    /** Returns whatever is still reserved for the order to available. Safe to call any number of times. */
    public Reservation release(UUID orderId) {
        return transactions.execute(() -> {
            Reservation current = reservations.findForUpdate(orderId).orElse(null);
            if (current == null) {
                reservations.insertTombstone(orderId, clock.instant());
                return reservations.find(orderId).orElseThrow();
            }
            if (current.status() == Reservation.Status.CLOSED) {
                return current;
            }
            if (current.remainingAmount().signum() > 0) {
                ledger.post(LedgerTransaction.transfer(LedgerTxnType.RELEASE, "release:" + orderId,
                        reserved(current.accountId(), current.asset()), available(current.accountId(), current.asset()),
                        current.remainingAmount()));
            }
            reservations.close(orderId, clock.instant());
            return reservations.find(orderId).orElseThrow();
        });
    }

    private static boolean matches(Reservation existing, UUID accountId, String asset, BigDecimal amount) {
        return accountId.equals(existing.accountId()) && asset.equals(existing.asset())
                && amount.compareTo(existing.reservedAmount()) == 0;
    }

    static LedgerAccountKey available(UUID accountId, String asset) {
        return LedgerAccountKey.USD.equals(asset)
                ? LedgerAccountKey.cash(accountId) : LedgerAccountKey.position(accountId, asset);
    }

    static LedgerAccountKey reserved(UUID accountId, String asset) {
        return LedgerAccountKey.USD.equals(asset)
                ? LedgerAccountKey.cashReserved(accountId) : LedgerAccountKey.positionReserved(accountId, asset);
    }
}
