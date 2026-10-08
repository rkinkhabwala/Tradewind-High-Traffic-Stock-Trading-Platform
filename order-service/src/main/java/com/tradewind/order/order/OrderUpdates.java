package com.tradewind.order.order;

import com.tradewind.common.userevent.UserEventPublisher;
import com.tradewind.order.order.OrderDtos.OrderView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Every order status change passes through here (the repository's two status writers call it). It is pushed to
 * the account's sockets and counted: {@code orders.status{status}} gives orders/sec by outcome, and
 * {@code orders.fill.latency{type}} measures order creation to FILLED (wall clock, both stamped by order-service).
 * <p>
 * Pushes every status change (accepted, rejected, partially filled, filled, canceled, expired) to the account's
 * sockets as {@code {"type":"order","order":{...}}}, with the same order view the REST API returns. Sent after the
 * change commits, best effort; GET /api/orders stays the source of truth.
 */
@Component
class OrderUpdates {

    record OrderMessage(String type, OrderView order) {
    }

    private final UserEventPublisher publisher;
    private final Map<OrderStatus, Counter> statusCounters = new EnumMap<>(OrderStatus.class);
    private final Timer marketFillLatency;
    private final Timer limitFillLatency;

    OrderUpdates(UserEventPublisher publisher, MeterRegistry meters) {
        this.publisher = publisher;
        for (OrderStatus status : OrderStatus.values()) {
            statusCounters.put(status, Counter.builder("orders.status").tag("status", status.name())
                    .description("Order status changes").register(meters));
        }
        this.marketFillLatency = fillTimer(meters, "MARKET");
        this.limitFillLatency = fillTimer(meters, "LIMIT");
    }

    private static Timer fillTimer(MeterRegistry meters, String type) {
        return Timer.builder("orders.fill.latency").tag("type", type)
                .description("Order created to FILLED in order-service")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(5)).maximumExpectedValue(Duration.ofMinutes(5))
                .register(meters);
    }

    void changed(Order order) {
        statusCounters.get(order.status()).increment();
        if (order.status() == OrderStatus.FILLED) {
            Duration latency = Duration.between(order.createdAt(), order.updatedAt());
            (order.type() == com.tradewind.common.order.OrderType.MARKET ? marketFillLatency : limitFillLatency)
                    .record(latency);
        }
        publisher.publishAfterCommit(order.accountId(), new OrderMessage("order", OrderView.of(order)));
    }
}
