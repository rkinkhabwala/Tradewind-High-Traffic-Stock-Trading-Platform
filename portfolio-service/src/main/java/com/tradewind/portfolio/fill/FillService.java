package com.tradewind.portfolio.fill;

import com.tradewind.common.execution.ExecutionReported;
import com.tradewind.common.money.Money;
import com.tradewind.common.money.Notional;
import com.tradewind.common.order.OrderSide;
import com.tradewind.portfolio.events.PortfolioEvents;
import com.tradewind.portfolio.ledger.LedgerAccountKey;
import com.tradewind.portfolio.ledger.LedgerAmounts;
import com.tradewind.portfolio.ledger.LedgerService;
import com.tradewind.portfolio.ledger.LedgerTransaction;
import com.tradewind.portfolio.ledger.LedgerTransactions;
import com.tradewind.portfolio.ledger.LedgerTxnType;
import com.tradewind.portfolio.ledger.Posting;
import com.tradewind.portfolio.position.Position;
import com.tradewind.portfolio.position.PositionRepository;
import com.tradewind.portfolio.reservation.Reservation;
import com.tradewind.portfolio.reservation.ReservationService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Applies execution events to the books (spec 6.3 step 7). One transaction per event: processed-event marker,
 * ledger postings, reservation consumption, positions read model, release of what is left on a terminal event,
 * and PortfolioUpdated in the outbox. A redelivered event finds its marker and changes nothing.
 * <pre>
 * BUY  fill: CASH_RESERVED -amount, HOUSE(USD) +amount, HOUSE(sym) -qty, POSITION(sym) +qty
 * SELL fill: POSITION_RESERVED(sym) -qty, HOUSE(sym) +qty, HOUSE(USD) -amount, CASH +amount
 * </pre>
 * {@code amount} rounds half-even on the order's cumulative notional, so partial fills never drift.
 */
@Service
public class FillService {

    private final LedgerService ledger;
    private final LedgerTransactions transactions;
    private final ReservationService reservations;
    private final PositionRepository positions;
    private final PortfolioEvents events;
    private final JdbcClient jdbc;
    private final Clock clock;

    FillService(LedgerService ledger, LedgerTransactions transactions, ReservationService reservations,
                PositionRepository positions, PortfolioEvents events, JdbcClient jdbc, Clock clock) {
        this.ledger = ledger;
        this.transactions = transactions;
        this.reservations = reservations;
        this.positions = positions;
        this.events = events;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void onExecution(UUID eventId, ExecutionReported e) {
        transactions.execute(() -> {
            if (!markProcessed(eventId)) {
                return null;
            }
            Reservation reservation = reservations.lockOpen(e.orderId());
            BigDecimal before = reservation.filledNotional();
            BigDecimal after = before.add(Notional.exact(e.qty(), e.price()));
            Money amount = Notional.fillIncrement(before, after);
            BigDecimal dollars = LedgerAmounts.of(amount);
            BigDecimal shares = LedgerAmounts.of(e.qty());
            UUID account = e.accountId();
            String symbol = e.symbol();

            List<Posting> postings = new ArrayList<>();
            if (e.side() == OrderSide.BUY) {
                postings.add(new Posting(LedgerAccountKey.houseShares(symbol), shares.negate()));
                postings.add(new Posting(LedgerAccountKey.position(account, symbol), shares));
                if (amount.isPositive()) {      // a tiny fractional fill can round to zero cents
                    postings.add(new Posting(LedgerAccountKey.cashReserved(account), dollars.negate()));
                    postings.add(new Posting(LedgerAccountKey.houseCash(), dollars));
                }
            } else {
                postings.add(new Posting(LedgerAccountKey.positionReserved(account, symbol), shares.negate()));
                postings.add(new Posting(LedgerAccountKey.houseShares(symbol), shares));
                if (amount.isPositive()) {
                    postings.add(new Posting(LedgerAccountKey.houseCash(), dollars.negate()));
                    postings.add(new Posting(LedgerAccountKey.cash(account), dollars));
                }
            }
            ledger.post(new LedgerTransaction(LedgerTxnType.FILL, "fill:" + e.executionId(), postings));
            reservations.consume(e.orderId(), e.side() == OrderSide.BUY ? dollars : shares, after);

            Position updated = positions.findForUpdate(account, symbol)
                    .map(p -> e.side() == OrderSide.BUY ? p.afterBuy(e.qty(), e.price()) : p.afterSell(e.qty()))
                    .orElseGet(() -> {
                        if (e.side() == OrderSide.SELL) {
                            throw new IllegalStateException("Sell fill without a position: " + e.executionId());
                        }
                        return Position.firstBuy(account, symbol, e.qty(), e.price());
                    });
            positions.save(updated, clock.instant());

            if (e.terminal()) {
                reservations.releaseInTransaction(e.orderId());
            }
            events.updated(account, "FILL", "fill:" + e.executionId(), symbol);
            return null;
        });
    }

    /** OrderCanceled / OrderExpired: nothing more will fill, so the rest of the reservation goes back. */
    public void onOrderClosed(UUID eventId, UUID orderId) {
        transactions.execute(() -> {
            if (markProcessed(eventId)) {
                reservations.releaseInTransaction(orderId);
            }
            return null;
        });
    }

    private boolean markProcessed(UUID eventId) {
        return jdbc.sql("INSERT INTO processed_events (event_id, processed_at) VALUES (:id, :now) ON CONFLICT DO NOTHING")
                .param("id", eventId).param("now", Timestamp.from(clock.instant()))
                .update() == 1;
    }
}
