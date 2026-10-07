package com.tradewind.order.order;

import com.tradewind.order.portfolio.ReservationClient;
import com.tradewind.order.portfolio.ReservationClient.ReservationUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Recovers orders stuck in PENDING (instance crashed mid-placement, or the reservation outcome stayed unknown).
 * Order matters: the order is first moved PENDING -> REJECTED (compare-and-set, so it cannot race the accept
 * path), and only then is its reservation released. Release is idempotent and leaves a tombstone if no
 * reservation exists yet, so a late reserve cannot strand money either.
 */
@Component
public class PendingOrderSweeper {

    private static final Logger log = LoggerFactory.getLogger(PendingOrderSweeper.class);
    static final int BATCH = 100;

    private final OrderRepository orders;
    private final ReservationClient reservations;
    private final Clock clock;
    private final Duration pendingTimeout;

    PendingOrderSweeper(OrderRepository orders, ReservationClient reservations, Clock clock,
                        @Value("${tradewind.orders.pending-timeout:30s}") Duration pendingTimeout) {
        this.orders = orders;
        this.reservations = reservations;
        this.clock = clock;
        this.pendingTimeout = pendingTimeout;
    }

    @Scheduled(fixedDelayString = "${tradewind.orders.sweep-interval:5s}")
    public void sweep() {
        for (UUID id : orders.stalePending(clock.instant().minus(pendingTimeout), BATCH)) {
            if (orders.transition(id, OrderStatus.PENDING, OrderStatus.REJECTED, "RESERVATION_TIMEOUT", true,
                    clock.instant()) == 1) {
                log.warn("Order {} was stuck in PENDING; rejected and queued for reservation release", id);
            }
        }
        for (UUID id : orders.awaitingRelease(BATCH)) {
            try {
                reservations.release(id);
                orders.clearReleasePending(id);
            } catch (ReservationUnavailableException e) {
                log.warn("Releasing reservation of order {} failed, will retry: {}", id, e.getMessage());
                return;
            }
        }
    }
}
