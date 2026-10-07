package com.tradewind.order.order;

import com.tradewind.common.money.Price;
import com.tradewind.common.money.Quantity;
import com.tradewind.common.order.OrderSide;
import com.tradewind.common.order.OrderType;
import com.tradewind.common.order.TimeInForce;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class OrderDtos {

    private OrderDtos() {
    }

    /** {@code qty} and {@code limitPrice} are decimal strings, e.g. "1.5" and "187.25". */
    public record PlaceOrderRequest(@NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9.]{0,9}") String symbol,
                                    @NotNull OrderSide side, @NotNull OrderType type, TimeInForce timeInForce,
                                    @NotNull Quantity qty, Price limitPrice) {

        public TimeInForce timeInForceOrDefault() {
            return timeInForce == null ? TimeInForce.DAY : timeInForce;
        }

        public String normalizedSymbol() {
            return symbol.toUpperCase(Locale.ROOT);
        }
    }

    public record OrderView(UUID id, String clientOrderId, String symbol, OrderSide side, OrderType type,
                            TimeInForce timeInForce, String qty, String limitPrice, OrderStatus status,
                            String filledQty, String avgFillPrice, String rejectReason, Instant cancelRequestedAt,
                            Instant createdAt, Instant updatedAt) {

        public static OrderView of(Order o) {
            return new OrderView(o.id(), o.clientOrderId(), o.symbol(), o.side(), o.type(), o.timeInForce(),
                    o.quantity().toJson(), o.limitPrice() == null ? null : o.limitPrice().toJson(), o.status(),
                    o.filledQuantity().toJson(), o.avgFillPrice() == null ? null : o.avgFillPrice().toJson(),
                    o.rejectReason(), o.cancelRequestedAt(), o.createdAt(), o.updatedAt());
        }
    }

    public record OrderPage(List<OrderView> items, String nextCursor) {
    }
}
