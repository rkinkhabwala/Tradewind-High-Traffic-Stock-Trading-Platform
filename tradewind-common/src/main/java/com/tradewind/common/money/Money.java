package com.tradewind.common.money;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An amount of US dollars held as whole cents. Negative values are allowed (ledger debits, house accounts).
 * Serialized to JSON as an integer number of cents.
 */
public record Money(long cents) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static Money ofCents(long cents) {
        return new Money(cents);
    }

    /** Exact conversion. Throws {@link ArithmeticException} if {@code dollars} has sub-cent precision. */
    public static Money ofDollars(BigDecimal dollars) {
        return fromScaledDollars(dollars.setScale(2, RoundingMode.UNNECESSARY));
    }

    /** Rounds a dollar amount to cents with banker's rounding. Used for fill amounts. */
    public static Money roundHalfEven(BigDecimal dollars) {
        return fromScaledDollars(dollars.setScale(2, RoundingMode.HALF_EVEN));
    }

    /** Rounds a dollar amount up to the next cent. Used for reservations, which must always cover the fill. */
    public static Money roundUp(BigDecimal dollars) {
        return fromScaledDollars(dollars.setScale(2, RoundingMode.CEILING));
    }

    private static Money fromScaledDollars(BigDecimal scaled) {
        return new Money(scaled.movePointRight(2).longValueExact());
    }

    @JsonValue
    @Override
    public long cents() {
        return cents;
    }

    public BigDecimal toDollars() {
        return BigDecimal.valueOf(cents, 2);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(cents, other.cents));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(cents, other.cents));
    }

    public Money negate() {
        return new Money(Math.negateExact(cents));
    }

    public boolean isNegative() {
        return cents < 0;
    }

    public boolean isPositive() {
        return cents > 0;
    }

    public boolean isZero() {
        return cents == 0;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(cents, other.cents);
    }

    @Override
    public String toString() {
        return toDollars().toPlainString() + " USD";
    }
}
