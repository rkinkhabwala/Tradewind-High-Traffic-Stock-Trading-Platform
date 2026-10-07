package com.tradewind.common.money;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A number of shares, fractional to 6 decimal places (matches {@code NUMERIC(19,6)}).
 * Always normalized to scale 6 so equal quantities are {@code equals()}. Serialized to JSON as a string.
 */
public record Quantity(BigDecimal value) implements Comparable<Quantity> {

    public static final int SCALE = 6;
    public static final Quantity ZERO = new Quantity(BigDecimal.ZERO);

    /** Throws {@link ArithmeticException} if {@code value} has more than 6 decimal places. */
    public Quantity {
        Objects.requireNonNull(value, "value");
        value = value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static Quantity of(String value) {
        return new Quantity(new BigDecimal(value));
    }

    public static Quantity of(BigDecimal value) {
        return new Quantity(value);
    }

    @JsonValue
    public String toJson() {
        return value.toPlainString();
    }

    public Quantity plus(Quantity other) {
        return new Quantity(value.add(other.value));
    }

    public Quantity minus(Quantity other) {
        return new Quantity(value.subtract(other.value));
    }

    public Quantity negate() {
        return new Quantity(value.negate());
    }

    public Quantity min(Quantity other) {
        return compareTo(other) <= 0 ? this : other;
    }

    public boolean isPositive() {
        return value.signum() > 0;
    }

    public boolean isNegative() {
        return value.signum() < 0;
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    @Override
    public int compareTo(Quantity other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}
