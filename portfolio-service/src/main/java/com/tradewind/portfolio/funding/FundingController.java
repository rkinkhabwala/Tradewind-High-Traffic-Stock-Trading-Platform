package com.tradewind.portfolio.funding;

import com.tradewind.common.money.Money;
import com.tradewind.portfolio.funding.FundingService.FundingResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Internal only. account-service calls these with a reference derived from the client's Idempotency-Key. */
@RestController
@RequestMapping("/internal/ledger")
class FundingController {

    record FundingRequest(@NotNull UUID referenceId, @NotNull UUID accountId, @NotNull @Positive Long amountCents) {
    }

    record FundingResponse(UUID referenceId, long cashBalanceCents) {
        static FundingResponse of(FundingResult result) {
            return new FundingResponse(result.referenceId(), result.cashBalance().cents());
        }
    }

    private final FundingService funding;

    FundingController(FundingService funding) {
        this.funding = funding;
    }

    @PostMapping("/deposits")
    FundingResponse deposit(@Valid @RequestBody FundingRequest request) {
        return FundingResponse.of(funding.deposit(request.referenceId(), request.accountId(),
                Money.ofCents(request.amountCents())));
    }

    @PostMapping("/withdrawals")
    FundingResponse withdraw(@Valid @RequestBody FundingRequest request) {
        return FundingResponse.of(funding.withdraw(request.referenceId(), request.accountId(),
                Money.ofCents(request.amountCents())));
    }
}
