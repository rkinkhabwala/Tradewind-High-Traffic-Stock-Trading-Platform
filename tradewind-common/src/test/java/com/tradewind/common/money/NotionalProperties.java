package com.tradewind.common.money;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotionalProperties {

    record Fill(Quantity qty, Price price) {
    }

    @Provide
    Arbitrary<List<Fill>> fills() {
        Arbitrary<Quantity> qty = Arbitraries.longs().between(1, 100_000_000_000L)       // up to 100k shares
                .map(micros -> Quantity.of(BigDecimal.valueOf(micros, Quantity.SCALE)));
        Arbitrary<Price> price = Arbitraries.longs().between(1, 10_000_000L)            // up to $100k
                .map(Price::ofCents);
        return Combinators.combine(qty, price).as(Fill::new).list().ofMinSize(1).ofMaxSize(20);
    }

    @Property
    void partialFillIncrementsSumToRoundedTotal(@ForAll("fills") List<Fill> fills) {
        BigDecimal cumulative = BigDecimal.ZERO;
        Money posted = Money.ZERO;
        for (Fill fill : fills) {
            BigDecimal next = cumulative.add(Notional.exact(fill.qty(), fill.price()));
            posted = posted.plus(Notional.fillIncrement(cumulative, next));
            cumulative = next;
        }
        assertThat(posted).isEqualTo(Money.roundHalfEven(cumulative));
    }

    @Property
    void limitBuyFillsNeverExceedReservation(@ForAll("fills") List<Fill> fills,
                                             @ForAll @LongRange(min = 0, max = 500) long limitAboveBestFillCents) {
        Quantity totalQty = Quantity.ZERO;
        BigDecimal cumulative = BigDecimal.ZERO;
        Money posted = Money.ZERO;
        Price highestFill = fills.getFirst().price();
        for (Fill fill : fills) {
            totalQty = totalQty.plus(fill.qty());
            BigDecimal next = cumulative.add(Notional.exact(fill.qty(), fill.price()));
            posted = posted.plus(Notional.fillIncrement(cumulative, next));
            cumulative = next;
            if (fill.price().compareTo(highestFill) > 0) {
                highestFill = fill.price();
            }
        }
        Price limit = Price.of(highestFill.value().add(BigDecimal.valueOf(limitAboveBestFillCents, 2)));

        Money reserved = Notional.buyReservation(totalQty, limit, BigDecimal.ZERO);

        assertThat(posted).isLessThanOrEqualTo(reserved);
    }
}
