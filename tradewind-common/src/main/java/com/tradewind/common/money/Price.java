package com.tradewind.common.money;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A strictly positive per-share price in dollars, to 4 decimal places (matches {@code NUMERIC(19,4)}).
 * Quotes are generated on a $0.01 tick; the extra precision holds averages such as avg fill price.
 * Serialized to JSON as a string.
 */
public record Price(BigDecimal value) implements Comparable<Price> {

    public static final int SCALE = 4;

    /** Throws {@link ArithmeticException} on more than 4 decimal places, {@link IllegalArgumentException} if not positive. */
    public Price {
        Objects.requireNonNull(value, "value");
        value = value.setScale(SCALE, RoundingMode.UNNECESSARY);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("price must be positive: " + value);
        }
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static Price of(String value) {
        return new Price(new BigDecimal(value));
    }

    public static Price of(BigDecimal value) {
        return new Price(value);
    }

    public static Price ofCents(long cents) {
        return new Price(BigDecimal.valueOf(cents, 2));
    }

    @JsonValue
    public String toJson() {
        return value.toPlainString();
    }

    @Override
    public int compareTo(Price other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}
