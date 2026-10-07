package com.tradewind.portfolio.reservation;

import com.tradewind.portfolio.events.PortfolioEvents;
import com.tradewind.portfolio.ledger.LedgerAccountKey;
import com.tradewind.portfolio.ledger.LedgerExceptions.ConflictingReferenceException;
import com.tradewind.portfolio.ledger.LedgerService;
import com.tradewind.portfolio.ledger.LedgerTransaction;
import com.tradewind.portfolio.ledger.LedgerTransactions;
import com.tradewind.portfolio.ledger.LedgerTxnType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

/**
 * Reserve/release API used synchronously by order-service (spec 6.3 step 3). Both operations are idempotent
 * per order id, so order-service can retry after a timeout without double-reserving. Fills consume reservations
 * and terminal order events release what is left (see FillService).
 */
@Service
public class ReservationService {

    private final ReservationRepository reservations;
    private final LedgerService ledger;
    private final LedgerTransactions transactions;
    private final PortfolioEvents events;
    private final Clock clock;

    ReservationService(ReservationRepository reservations, LedgerService ledger, LedgerTransactions transactions,
                       PortfolioEvents events, Clock clock) {
        this.reservations = reservations;
        this.ledger = ledger;
        this.transactions = transactions;
        this.events = events;
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
            events.updated(accountId, "RESERVE", "reserve:" + orderId, symbolOf(asset));
            return reservations.find(orderId).orElseThrow();
        });
    }

    /** Returns whatever is still reserved for the order to available. Safe to call any number of times. */
    public Reservation release(UUID orderId) {
        return transactions.execute(() -> releaseInTransaction(orderId));
    }

    /** {@link #release} inside the caller's transaction (fill processing releases on the terminal event). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Reservation releaseInTransaction(UUID orderId) {
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
        events.updated(current.accountId(), "RELEASE", "release:" + orderId, symbolOf(current.asset()));
        return reservations.find(orderId).orElseThrow();
    }

    /** Locks the order's open reservation for a fill. A fill without one would be a bug upstream. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Reservation lockOpen(UUID orderId) {
        Reservation reservation = reservations.findForUpdate(orderId)
                .orElseThrow(() -> new IllegalStateException("Fill for order " + orderId + " without a reservation"));
        if (reservation.status() != Reservation.Status.OPEN) {
            throw new IllegalStateException("Fill for order " + orderId + " after its reservation was closed");
        }
        return reservation;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(UUID orderId, BigDecimal amount, BigDecimal filledNotional) {
        reservations.consume(orderId, amount, filledNotional, clock.instant());
    }

    private static boolean matches(Reservation existing, UUID accountId, String asset, BigDecimal amount) {
        return accountId.equals(existing.accountId()) && asset.equals(existing.asset())
                && amount.compareTo(existing.reservedAmount()) == 0;
    }

    private static String symbolOf(String asset) {
        return LedgerAccountKey.USD.equals(asset) ? null : asset;
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
