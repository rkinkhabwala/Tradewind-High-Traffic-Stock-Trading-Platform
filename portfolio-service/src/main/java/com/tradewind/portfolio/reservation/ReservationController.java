package com.tradewind.portfolio.reservation;

import com.tradewind.common.money.Money;
import com.tradewind.common.money.Quantity;
import com.tradewind.portfolio.ledger.LedgerAccountKey;
import com.tradewind.portfolio.ledger.LedgerAmounts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/** Internal only: never routed by the gateway. */
@RestController
@RequestMapping("/internal/reservations")
class ReservationController {

    /** Exactly one of {@code cashCents} (buy) or {@code symbol}+{@code quantity} (sell). */
    record ReserveRequest(@NotNull UUID orderId, @NotNull UUID accountId,
                          @Positive Long cashCents,
                          @Pattern(regexp = "[A-Z][A-Z0-9.]{0,9}") String symbol,
                          Quantity quantity) {
    }

    /** Amounts are plain decimal strings in the asset's unit (dollars or shares), never scientific notation. */
    record ReservationResponse(UUID orderId, UUID accountId, String asset, String reserved,
                               String remaining, Reservation.Status status) {
        static ReservationResponse of(Reservation r) {
            return new ReservationResponse(r.orderId(), r.accountId(), r.asset(),
                    plain(r.reservedAmount()), plain(r.remainingAmount()), r.status());
        }

        private static String plain(BigDecimal amount) {
            return amount.stripTrailingZeros().toPlainString();
        }
    }

    private final ReservationService reservations;

    ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    @PostMapping
    ReservationResponse reserve(@Valid @RequestBody ReserveRequest request) {
        boolean cash = request.cashCents() != null;
        boolean shares = request.symbol() != null && request.quantity() != null;
        if (cash == shares || request.quantity() != null && !request.quantity().isPositive()) {
            throw new IllegalArgumentException("Provide either cashCents, or symbol and a positive quantity");
        }
        Reservation reservation = cash
                ? reservations.reserve(request.orderId(), request.accountId(), LedgerAccountKey.USD,
                LedgerAmounts.of(Money.ofCents(request.cashCents())))
                : reservations.reserve(request.orderId(), request.accountId(), request.symbol(),
                LedgerAmounts.of(request.quantity()));
        return ReservationResponse.of(reservation);
    }

    @PostMapping("/{orderId}/release")
    ReservationResponse release(@PathVariable UUID orderId) {
        return ReservationResponse.of(reservations.release(orderId));
    }
}
