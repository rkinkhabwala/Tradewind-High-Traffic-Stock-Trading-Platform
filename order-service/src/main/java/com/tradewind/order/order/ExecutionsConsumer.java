package com.tradewind.order.order;

import com.tradewind.common.event.EventJson;
import com.tradewind.common.event.EventTypes;
import com.tradewind.common.execution.ExecutionReported;
import com.tradewind.common.kafka.EventConsumerLoop;
import com.tradewind.common.kafka.Topics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Consumes executions in the order-service consumer group (independent of portfolio-service's). */
@Component
class ExecutionsConsumer implements SmartLifecycle {

    private final EventConsumerLoop loop;

    ExecutionsConsumer(OrderStatusUpdater updater, @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                       @Value("${tradewind.orders.consumer-group:order-service}") String groupId) {
        this.loop = new EventConsumerLoop("order-executions", bootstrapServers, groupId,
                List.of(Topics.EXECUTIONS), (event, record) -> {
            switch (event.eventType()) {
                case EventTypes.EXECUTION_REPORTED ->
                        updater.onExecution(event.eventId(), EventJson.convert(event.payload(), ExecutionReported.class));
                case EventTypes.ORDER_CANCELED -> {
                    String reason = event.payload().get("reason").asText();
                    updater.onClosed(event.eventId(), orderId(event.payload()), OrderStatus.CANCELED,
                            "USER_REQUESTED".equals(reason) ? null : reason);
                }
                case EventTypes.ORDER_EXPIRED ->
                        updater.onClosed(event.eventId(), orderId(event.payload()), OrderStatus.EXPIRED, null);
                default -> { }  // CancelRejected: the order already reached its terminal state via another event
            }
        });
    }

    private static UUID orderId(com.fasterxml.jackson.databind.JsonNode payload) {
        return UUID.fromString(payload.get("orderId").asText());
    }

    @Override
    public void start() {
        loop.start();
    }

    @Override
    public void stop() {
        loop.close();
    }

    @Override
    public boolean isRunning() {
        return loop.isRunning();
    }
}
