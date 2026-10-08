package com.tradewind.portfolio.fill;

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

/**
 * Consumes executions (keyed by account, so one account's fills and releases arrive in order) in the
 * portfolio-service consumer group. Instances split the partitions between them.
 */
@Component
class ExecutionsConsumer implements SmartLifecycle {

    private final EventConsumerLoop loop;

    ExecutionsConsumer(FillService fills, @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                       @Value("${tradewind.portfolio.consumer-group:portfolio-service}") String groupId,
                       org.springframework.transaction.support.TransactionTemplate transactions,
                       @Value("${tradewind.consumer.batch-transactions:true}") boolean batchTransactions,
                       @Value("${tradewind.consumer.max-batch:25}") int maxBatch) {
        this.loop = new EventConsumerLoop("portfolio-executions", bootstrapServers, groupId,
                List.of(Topics.EXECUTIONS), (event, record) -> {
            switch (event.eventType()) {
                case EventTypes.EXECUTION_REPORTED ->
                        fills.onExecution(event.eventId(), EventJson.convert(event.payload(), ExecutionReported.class));
                case EventTypes.ORDER_CANCELED, EventTypes.ORDER_EXPIRED ->
                        fills.onOrderClosed(event.eventId(), UUID.fromString(event.payload().get("orderId").asText()));
                default -> { }  // CancelRejected changes nothing in the books
            }
        });
        if (batchTransactions) {
            // One transaction (one WAL sync) per poll instead of per record; falls back to per record on failure.
            loop.withBatchTransactions(work -> transactions.executeWithoutResult(status -> work.run()), maxBatch);
        }
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
