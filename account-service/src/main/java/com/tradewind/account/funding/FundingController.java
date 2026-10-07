package com.tradewind.account.funding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradewind.account.funding.FundingService.Direction;
import com.tradewind.account.web.Identity;
import com.tradewind.common.idempotency.IdempotencyKeys;
import com.tradewind.common.idempotency.StoredResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.UncheckedIOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/account")
class FundingController {

    record FundingRequest(@NotNull @Positive Long amountCents) {
    }

    private final FundingService funding;
    private final FundingProperties properties;
    private final ObjectMapper objectMapper;

    FundingController(FundingService funding, FundingProperties properties, ObjectMapper objectMapper) {
        this.funding = funding;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/deposits")
    ResponseEntity<String> deposit(@RequestHeader(value = Identity.ACCOUNT_ID_HEADER, required = false) UUID accountId,
                                   @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String key,
                                   @Valid @RequestBody FundingRequest request) {
        return handle(Direction.DEPOSIT, "/api/account/deposits", accountId, key, request);
    }

    @PostMapping("/withdrawals")
    ResponseEntity<String> withdraw(@RequestHeader(value = Identity.ACCOUNT_ID_HEADER, required = false) UUID accountId,
                                    @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String key,
                                    @Valid @RequestBody FundingRequest request) {
        return handle(Direction.WITHDRAWAL, "/api/account/withdrawals", accountId, key, request);
    }

    private ResponseEntity<String> handle(Direction direction, String path, UUID accountId, String key,
                                          FundingRequest request) {
        UUID account = Identity.require(accountId);
        String validKey = IdempotencyKeys.requireValid(key);
        if (request.amountCents() > properties.maxAmountCents()) {
            throw new IllegalArgumentException("amountCents must not exceed " + properties.maxAmountCents());
        }
        StoredResponse response = funding.move(direction, account, validKey,
                IdempotencyKeys.requestHash("POST", path, canonical(request)), request.amountCents());
        MediaType type = response.status() >= 400 ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.APPLICATION_JSON;
        return ResponseEntity.status(response.status()).contentType(type).body(response.body());
    }

    /** Re-serialized so formatting differences in the client's JSON do not count as a different request. */
    private byte[] canonical(FundingRequest request) {
        try {
            return objectMapper.writeValueAsBytes(request);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
