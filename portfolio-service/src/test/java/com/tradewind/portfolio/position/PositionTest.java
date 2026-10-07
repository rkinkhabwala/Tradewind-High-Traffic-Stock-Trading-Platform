package com.tradewind.portfolio.position;

import com.tradewind.common.money.Price;
import com.tradewind.common.money.Quantity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PositionTest {

    private final UUID account = UUID.randomUUID();

    @Test
    void averageCostIsTheQuantityWeightedMeanOfBuys() {
        Position p = Position.firstBuy(account, "AAPL", Quantity.of("4"), Price.of("200.01"))
                .afterBuy(Quantity.of("6"), Price.of("200.02"));

        assertThat(p.qty()).isEqualTo(Quantity.of("10"));
        assertThat(p.avgCost()).isEqualByComparingTo("200.016");       // (800.04 + 1200.12) / 10
        assertThat(p.displayAvgCost()).isEqualTo(Price.of("200.0160"));
    }

    @Test
    void sellsReduceQuantityAndKeepAverageCost() {
        Position p = Position.firstBuy(account, "AAPL", Quantity.of("10"), Price.of("100"))
                .afterBuy(Quantity.of("10"), Price.of("110"))
                .afterSell(Quantity.of("15"));

        assertThat(p.qty()).isEqualTo(Quantity.of("5"));
        assertThat(p.avgCost()).isEqualByComparingTo("105");
    }

    @Test
    void fractionalBuysKeepPrecision() {
        Position p = Position.firstBuy(account, "AAPL", Quantity.of("0.333333"), Price.of("300"))
                .afterBuy(Quantity.of("0.666667"), Price.of("150"));

        // (99.9999 + 100.00005) / 1.000000 = 199.99995 exactly; displayed half-even to 4 places = 200.0000
        assertThat(p.avgCost()).isEqualByComparingTo("199.99995");
        assertThat(p.displayAvgCost()).isEqualTo(Price.of("200.0000"));
    }
}
