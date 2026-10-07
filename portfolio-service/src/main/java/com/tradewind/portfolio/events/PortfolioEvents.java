package com.tradewind.portfolio.events;

import com.tradewind.common.event.EventEnvelope;
import com.tradewind.common.event.EventTypes;
import com.tradewind.common.kafka.Topics;
import com.tradewind.common.money.Money;
import com.tradewind.common.money.Quantity;
import com.tradewind.common.outbox.OutboxWriter;
import com.tradewind.common.portfolio.PortfolioUpdated;
import com.tradewind.portfolio.ledger.LedgerAccountKey;
import com.tradewind.portfolio.ledger.LedgerAmounts;
import com.tradewind.portfolio.ledger.LedgerService;
import com.tradewind.portfolio.position.Position;
import com.tradewind.portfolio.position.PositionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** Writes PortfolioUpdated to the outbox in the same transaction as the ledger change it describes. */
@Component
public class PortfolioEvents {

    private final LedgerService ledger;
    private final PositionRepository positions;
    private final OutboxWriter outbox;
    private final Clock clock;

    PortfolioEvents(LedgerService ledger, PositionRepository positions, OutboxWriter outbox, Clock clock) {
        this.ledger = ledger;
        this.positions = positions;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void updated(UUID accountId, String reason, String referenceId, String symbol) {
        Money cash = LedgerAmounts.toMoney(ledger.balanceOf(LedgerAccountKey.cash(accountId)));
        Money reserved = LedgerAmounts.toMoney(ledger.balanceOf(LedgerAccountKey.cashReserved(accountId)));
        Quantity positionQty = symbol == null ? null
                : positions.find(accountId, symbol).map(Position::qty).orElse(Quantity.ZERO);
        PortfolioUpdated event = new PortfolioUpdated(accountId, reason, referenceId, cash, reserved, symbol,
                positionQty, clock.instant());
        outbox.append(Topics.LEDGER_EVENTS, accountId.toString(),
                EventEnvelope.of(EventTypes.PORTFOLIO_UPDATED, PortfolioUpdated.VERSION, accountId.toString(), event, clock));
    }
}
