package com.tradewind.correctness;

import com.tradewind.common.event.EventEnvelope;
import com.tradewind.common.event.EventJson;
import com.tradewind.common.event.EventTypes;
import com.tradewind.common.kafka.KafkaDefaults;
import com.tradewind.common.kafka.Topics;
import com.tradewind.common.money.Money;
import com.tradewind.common.money.Notional;
import com.tradewind.common.money.Price;
import com.tradewind.common.money.Quantity;
import com.tradewind.common.quote.QuoteTick;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Test-side helpers: publish ticks like marketdata-service, and compute what the books must say. */
final class Books {

    record Execution(int seq, BigDecimal qty, BigDecimal price) {
    }

    private final Cluster cluster;
    private final KafkaProducer<String, String> producer;

    Books(Cluster cluster) {
        this.cluster = cluster;
        this.producer = new KafkaProducer<>(KafkaDefaults.producer(cluster.kafka.getBootstrapServers()));
    }

    void tick(String symbol, String bid, String ask, long size) {
        QuoteTick tick = new QuoteTick(symbol, Price.of(bid), Price.of(ask), Price.of(bid), size, size, 0, Instant.now());
        producer.send(new ProducerRecord<>(Topics.QUOTES_TICKS.name(), symbol, EventJson.write(
                EventEnvelope.of(EventTypes.QUOTE_TICK, QuoteTick.VERSION, symbol, tick, Clock.systemUTC()))));
        producer.flush();
    }

    List<Execution> executions(UUID orderId) {
        return cluster.executionDb.sql("SELECT seq, qty, price FROM executions WHERE order_id = :o ORDER BY seq")
                .param("o", orderId)
                .query((rs, n) -> new Execution(rs.getInt("seq"), rs.getBigDecimal("qty"), rs.getBigDecimal("price")))
                .list();
    }

    /** What a buy order's fills must have cost: half-even on the cumulative exact notional (spec 4). */
    static Money cost(List<Execution> fills) {
        BigDecimal cumulative = BigDecimal.ZERO;
        Money total = Money.ZERO;
        for (Execution fill : fills) {
            BigDecimal next = cumulative.add(Notional.exact(Quantity.of(fill.qty()), Price.of(fill.price())));
            total = total.plus(Notional.fillIncrement(cumulative, next));
            cumulative = next;
        }
        return total;
    }

    static BigDecimal filled(List<Execution> fills) {
        return fills.stream().map(Execution::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
