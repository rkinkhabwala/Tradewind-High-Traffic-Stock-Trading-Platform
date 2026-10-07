package com.tradewind.order.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradewind.common.event.EventEnvelope;
import com.tradewind.common.event.EventTypes;
import com.tradewind.common.idempotency.CachingIdempotentExecutor;
import com.tradewind.common.idempotency.StoredResponse;
import com.tradewind.common.kafka.Topics;
import com.tradewind.common.order.CancelRequested;
import com.tradewind.common.order.OrderAccepted;
import com.tradewind.common.order.OrderSide;
import com.tradewind.common.outbox.OutboxWriter;
import com.tradewind.common.quote.QuoteHash.StoredQuote;
import com.tradewind.order.order.OrderDtos.OrderView;
import com.tradewind.order.order.OrderDtos.PlaceOrderRequest;
import com.tradewind.order.order.OrderValidator.OrderValidationException;
import com.tradewind.order.portfolio.ReservationClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Order placement and cancellation (spec 6.3, 6.4). Placement:
 * <ol>
 *   <li>Validate, then insert the order as PENDING (client_order_id = Idempotency-Key, unique per account).</li>
 *   <li>Reserve buying power or shares synchronously (timeout, bulkhead, circuit breaker).</li>
 *   <li>One transaction: PENDING -> ACCEPTED plus the OrderAccepted outbox row; or PENDING -> REJECTED.</li>
 * </ol>
 * If the reservation outcome is unknown the order stays PENDING and the client gets 503; retrying with the same
 * key resumes this same order. Abandoned PENDING orders are handled by {@link PendingOrderSweeper}.
 */
@Service
public class OrderService {

    private final OrderRepository orders;
    private final OrderValidator validator;
    private final ReservationClient reservations;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final CachingIdempotentExecutor idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    OrderService(OrderRepository orders, OrderValidator validator, ReservationClient reservations, OutboxWriter outbox,
                 TransactionTemplate transactions, CachingIdempotentExecutor idempotency, ObjectMapper objectMapper,
                 Clock clock) {
        this.orders = orders;
        this.validator = validator;
        this.reservations = reservations;
        this.outbox = outbox;
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Thrown when cancelling an order whose placement is still in flight: retryable, not recorded. */
    public static class OrderNotYetAcceptedException extends RuntimeException {
        public OrderNotYetAcceptedException() {
            super("Order is still being placed; retry shortly");
        }
    }

    public StoredResponse place(UUID accountId, String idempotencyKey, String requestHash, PlaceOrderRequest request) {
        return idempotency.execute(accountId, idempotencyKey, requestHash, () -> {
            Optional<Order> existing = orders.findByClientOrderId(accountId, idempotencyKey);
            Order order;
            if (existing.isPresent()) {
                order = existing.get();     // a retry or takeover of an earlier attempt: continue that order
            } else {
                StoredQuote quote;
                try {
                    quote = validator.validate(request);
                } catch (OrderValidationException e) {
                    return problem(HttpStatus.UNPROCESSABLE_ENTITY, e.code(), e.getMessage(), null);
                }
                order = newPendingOrder(accountId, idempotencyKey, request, quote);
                if (!orders.insertPending(order)) {
                    order = orders.findByClientOrderId(accountId, idempotencyKey).orElseThrow();
                }
            }
            if (order.status() != OrderStatus.PENDING) {
                return placementResponse(order);
            }
            return placementResponse(reserveAndFinalize(order));
        });
    }

    public StoredResponse cancel(UUID accountId, String idempotencyKey, String requestHash, UUID orderId) {
        return idempotency.execute(accountId, idempotencyKey, requestHash, () -> {
            Optional<Order> found = orders.find(orderId).filter(o -> o.accountId().equals(accountId));
            if (found.isEmpty()) {
                return problem(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order " + orderId + " not found", null);
            }
            Order order = found.get();
            if (order.status() == OrderStatus.PENDING) {
                throw new OrderNotYetAcceptedException();
            }
            if (!order.status().isOpen()) {
                return problem(HttpStatus.CONFLICT, "ORDER_NOT_OPEN",
                        "Order is " + order.status() + " and can no longer be canceled", orderId);
            }
            transactions.executeWithoutResult(status -> {
                Instant now = clock.instant();
                if (orders.requestCancel(orderId, now) == 1) {
                    CancelRequested event = new CancelRequested(orderId, accountId, order.symbol(), now);
                    outbox.append(Topics.ORDERS_SUBMITTED, orderId.toString(), EventEnvelope.of(
                            EventTypes.CANCEL_REQUESTED, CancelRequested.VERSION, order.symbol(), event, clock));
                }
            });
            // 202: the cancel is requested; execution decides whether it wins against a concurrent fill.
            return json(HttpStatus.ACCEPTED, OrderView.of(orders.find(orderId).orElseThrow()));
        });
    }

    public Optional<Order> find(UUID accountId, UUID orderId) {
        return orders.find(orderId).filter(o -> o.accountId().equals(accountId));
    }

    private Order reserveAndFinalize(Order order) {
        ReservationClient.Outcome outcome = order.side() == OrderSide.BUY
                ? reservations.reserveCash(order.id(), order.accountId(), order.reservedCashCents())
                : reservations.reserveShares(order.id(), order.accountId(), order.symbol(), order.quantity());
        Instant now = clock.instant();
        switch (outcome) {
            case ReservationClient.Reserved r -> transactions.executeWithoutResult(status -> {
                // Conditional on PENDING: if the sweeper rejected the order meanwhile, it also releases the funds.
                if (orders.transition(order.id(), OrderStatus.PENDING, OrderStatus.ACCEPTED, null, false, now) == 1) {
                    OrderAccepted event = new OrderAccepted(order.id(), order.accountId(), order.symbol(), order.side(),
                            order.type(), order.timeInForce(), order.quantity(), order.limitPrice(), order.priceCap(), now);
                    outbox.append(Topics.ORDERS_SUBMITTED, order.id().toString(), EventEnvelope.of(
                            EventTypes.ORDER_ACCEPTED, OrderAccepted.VERSION, order.symbol(), event, clock));
                }
            });
            case ReservationClient.Insufficient insufficient ->
                    orders.transition(order.id(), OrderStatus.PENDING, OrderStatus.REJECTED, insufficient.code(), false, now);
            case ReservationClient.AlreadyReleased released ->
                    orders.transition(order.id(), OrderStatus.PENDING, OrderStatus.REJECTED, "RESERVATION_RELEASED", false, now);
        }
        return orders.find(order.id()).orElseThrow();
    }

    private Order newPendingOrder(UUID accountId, String clientOrderId, PlaceOrderRequest request, StoredQuote quote) {
        ReservationPlan plan = ReservationPlan.of(request.side(), request.type(), request.qty(), request.limitPrice(),
                quote.tick());
        Instant now = clock.instant();
        return new Order(UUID.randomUUID(), accountId, clientOrderId, request.normalizedSymbol(), request.side(),
                request.type(), request.timeInForceOrDefault(), request.qty(), request.limitPrice(), OrderStatus.PENDING,
                com.tradewind.common.money.Quantity.ZERO, null, null,
                plan.reservedCash() == null ? null : plan.reservedCash().cents(), plan.priceCap(), null, now, now);
    }

    /** 202 with the order once accepted (or later states); 422 problem carrying the order id when rejected. */
    private StoredResponse placementResponse(Order order) {
        if (order.status() == OrderStatus.REJECTED) {
            return problem(HttpStatus.UNPROCESSABLE_ENTITY, order.rejectReason(),
                    "Order rejected: " + order.rejectReason(), order.id());
        }
        return json(HttpStatus.ACCEPTED, OrderView.of(order));
    }

    private StoredResponse problem(HttpStatus status, String code, String detail, UUID orderId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        if (orderId != null) {
            problem.setProperty("orderId", orderId);
        }
        return json(status, problem);
    }

    private StoredResponse json(HttpStatus status, Object body) {
        try {
            return new StoredResponse(status.value(), objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
