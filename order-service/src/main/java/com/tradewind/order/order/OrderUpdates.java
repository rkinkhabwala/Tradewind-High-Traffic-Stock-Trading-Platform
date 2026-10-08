package com.tradewind.order.order;

import com.tradewind.common.userevent.UserEventPublisher;
import com.tradewind.order.order.OrderDtos.OrderView;
import org.springframework.stereotype.Component;

/**
 * Pushes every status change (accepted, rejected, partially filled, filled, canceled, expired) to the account's
 * sockets as {@code {"type":"order","order":{...}}}, with the same order view the REST API returns. Sent after the
 * change commits, best effort; GET /api/orders stays the source of truth.
 */
@Component
class OrderUpdates {

    record OrderMessage(String type, OrderView order) {
    }

    private final UserEventPublisher publisher;

    OrderUpdates(UserEventPublisher publisher) {
        this.publisher = publisher;
    }

    void changed(Order order) {
        publisher.publishAfterCommit(order.accountId(), new OrderMessage("order", OrderView.of(order)));
    }
}
