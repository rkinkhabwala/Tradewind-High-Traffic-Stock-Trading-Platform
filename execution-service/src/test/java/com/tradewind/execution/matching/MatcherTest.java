package com.tradewind.execution.matching;

import com.tradewind.common.money.Price;
import com.tradewind.common.money.Quantity;
import com.tradewind.common.order.OrderSide;
import com.tradewind.common.order.OrderType;
import com.tradewind.common.order.TimeInForce;
import com.tradewind.common.quote.QuoteTick;
import com.tradewind.execution.book.RestingOrder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MatcherTest {

    private static final Instant T0 = Instant.parse("2026-01-05T15:00:00Z");

    private static RestingOrder order(OrderSide side, OrderType type, String limit, String cap, String qty, int ageSeconds) {
        return new RestingOrder(UUID.randomUUID(), UUID.randomUUID(), "AAPL", side, type, TimeInForce.DAY,
                limit == null ? null : Price.of(limit), cap == null ? null : Price.of(cap), Quantity.of(qty),
                Quantity.of(qty), Quantity.ZERO, 1, LocalDate.parse("2026-01-05"), 0, T0.minusSeconds(ageSeconds));
    }

    private static QuoteTick quote(String bid, long bidSize, String ask, long askSize) {
        return new QuoteTick("AAPL", Price.of(bid), Price.of(ask), Price.of(bid), bidSize, askSize, 0, T0);
    }

    private static List<RestingOrder> sorted(List<RestingOrder> orders, OrderSide side) {
        TreeSet<RestingOrder> set = new TreeSet<>(side == OrderSide.BUY ? RestingOrder.BUY_PRIORITY : RestingOrder.SELL_PRIORITY);
        set.addAll(orders);
        return List.copyOf(set);
    }

    @Test
    void marketBuyFillsAtTheAskUpToTheDisplayedSize() {
        RestingOrder buy = order(OrderSide.BUY, OrderType.MARKET, null, "210.01", "500", 0);

        Matcher.Result result = Matcher.match(List.of(buy), List.of(), quote("199.99", 100, "200.01", 300));

        assertThat(result.fills()).singleElement().satisfies(f -> {
            assertThat(f.qty()).isEqualTo(Quantity.of("300"));
            assertThat(f.price()).isEqualTo(Price.of("200.01"));
        });
    }

    @Test
    void limitBuyOnlyFillsWhenTheAskReachesItsLimitAndGetsTheBetterPrice() {
        RestingOrder buy = order(OrderSide.BUY, OrderType.LIMIT, "199.00", "199.00", "10", 0);

        assertThat(Matcher.match(List.of(buy), List.of(), quote("198.99", 100, "199.01", 100)).isEmpty()).isTrue();
        Matcher.Result crossed = Matcher.match(List.of(buy), List.of(), quote("198.80", 100, "198.90", 100));
        assertThat(crossed.fills()).singleElement().extracting(Matcher.Fill::price).isEqualTo(Price.of("198.90"));
    }

    @Test
    void displayedSizeIsSharedByPriceThenTimePriority() {
        RestingOrder lowOld = order(OrderSide.BUY, OrderType.LIMIT, "199.00", "199.00", "10", 30);
        RestingOrder highNew = order(OrderSide.BUY, OrderType.LIMIT, "199.50", "199.50", "10", 1);
        RestingOrder lowNew = order(OrderSide.BUY, OrderType.LIMIT, "199.00", "199.00", "10", 5);

        Matcher.Result result = Matcher.match(sorted(List.of(lowNew, highNew, lowOld), OrderSide.BUY), List.of(),
                quote("198.90", 100, "199.00", 15));

        assertThat(result.fills()).extracting(f -> f.order().orderId(), f -> f.qty())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(highNew.orderId(), Quantity.of("10")),
                        org.assertj.core.groups.Tuple.tuple(lowOld.orderId(), Quantity.of("5")));
    }

    @Test
    void marketBuyRemainderAboveItsCollarIsCanceledNotFilled() {
        RestingOrder buy = order(OrderSide.BUY, OrderType.MARKET, null, "210.01", "900", 0);

        Matcher.Result result = Matcher.match(List.of(buy), List.of(), quote("210.90", 1_000, "211.00", 1_000));

        assertThat(result.fills()).isEmpty();
        assertThat(result.collarCancels()).containsExactly(buy);
    }

    @Test
    void sellsFillAtTheBidMarketFirstThenLowestLimit() {
        RestingOrder limitLow = order(OrderSide.SELL, OrderType.LIMIT, "199.00", null, "5", 10);
        RestingOrder limitHigh = order(OrderSide.SELL, OrderType.LIMIT, "201.00", null, "5", 20);
        RestingOrder market = order(OrderSide.SELL, OrderType.MARKET, null, null, "5", 0);

        Matcher.Result result = Matcher.match(List.of(), sorted(List.of(limitHigh, limitLow, market), OrderSide.SELL),
                quote("199.99", 8, "200.01", 100));

        assertThat(result.fills()).extracting(f -> f.order().orderId()).containsExactly(market.orderId(), limitLow.orderId());
        assertThat(result.fills()).extracting(Matcher.Fill::qty).containsExactly(Quantity.of("5"), Quantity.of("3"));
        assertThat(result.fills()).allSatisfy(f -> assertThat(f.price()).isEqualTo(Price.of("199.99")));
    }

    @Test
    void fractionalSharesFillExactly() {
        RestingOrder buy = order(OrderSide.BUY, OrderType.MARKET, null, "210.01", "0.333333", 0);

        Matcher.Result result = Matcher.match(List.of(buy), List.of(), quote("199.99", 100, "200.01", 100));

        assertThat(result.fills()).singleElement().extracting(Matcher.Fill::qty).isEqualTo(Quantity.of("0.333333"));
        assertThat(buy.afterFill(Quantity.of("0.333333")).remaining().isZero()).isTrue();
    }
}
