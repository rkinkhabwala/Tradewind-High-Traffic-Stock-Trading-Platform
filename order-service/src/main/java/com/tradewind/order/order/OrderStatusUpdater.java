package com.tradewind.order.order;

import com.tradewind.common.execution.ExecutionReported;
import com.tradewind.common.money.Notional;
import com.tradewind.common.money.Price;
import com.tradewind.order.order.OrderRepository.FillState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Spec 6.3 step 8: order-service follows execution events to keep filled_qty, avg_fill_price and status current.
 * Idempotent: processed-event marker in the same transaction, plus an execution-seq guard.
 */
@Service
public class OrderStatusUpdater {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusUpdater.class);

    private final OrderRepository orders;
    private final TransactionTemplate transactions;
    private final Clock clock;

    OrderStatusUpdater(OrderRepository orders, TransactionTemplate transactions, Clock clock) {
        this.orders = orders;
        this.transactions = transactions;
        this.clock = clock;
    }

    public void onExecution(UUID eventId, ExecutionReported e) {
        transactions.executeWithoutResult(status -> {
            if (!orders.markProcessed(eventId, clock.instant())) {
                return;
            }
            Optional<FillState> state = orders.lockFillState(e.orderId());
            if (state.isEmpty()) {
                log.warn("Execution {} for unknown order {}", e.executionId(), e.orderId());
                return;
            }
            FillState current = state.get();
            if (e.seq() <= current.lastExecSeq()) {
                return;     // duplicate of an execution already applied
            }
            OrderStatus target = e.terminal() ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
            if (!current.status().canTransitionTo(target)) {
                log.warn("Ignoring execution {}: order {} is {}", e.executionId(), e.orderId(), current.status());
                return;
            }
            BigDecimal notional = current.fillNotional().add(Notional.exact(e.qty(), e.price()));
            Price average = Price.of(notional.divide(e.cumulativeQty().value(), Price.SCALE, RoundingMode.HALF_EVEN));
            orders.applyFill(e.orderId(), current.status(), target, e.cumulativeQty(), average, notional, e.seq(),
                    clock.instant());
        });
    }

    /** OrderCanceled / OrderExpired. {@code reason} is kept for COLLAR_EXCEEDED so users can see why. */
    public void onClosed(UUID eventId, UUID orderId, OrderStatus target, String reason) {
        transactions.executeWithoutResult(status -> {
            if (!orders.markProcessed(eventId, clock.instant())) {
                return;
            }
            Optional<FillState> state = orders.lockFillState(orderId);
            if (state.isEmpty() || !state.get().status().canTransitionTo(target)) {
                log.warn("Ignoring {} for order {} in state {}", target, orderId, state.map(FillState::status).orElse(null));
                return;
            }
            orders.transition(orderId, state.get().status(), target, reason, false, clock.instant());
        });
    }
}
