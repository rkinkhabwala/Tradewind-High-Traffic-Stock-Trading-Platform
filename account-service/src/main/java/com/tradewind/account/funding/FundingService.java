package com.tradewind.account.funding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradewind.account.funding.PortfolioLedgerClient.InsufficientFundsException;
import com.tradewind.account.funding.PortfolioLedgerClient.LedgerResult;
import com.tradewind.account.user.AccountEntity;
import com.tradewind.account.user.AccountRepository;
import com.tradewind.account.user.AccountStatus;
import com.tradewind.common.idempotency.JdbcIdempotentExecutor;
import com.tradewind.common.idempotency.StoredResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Simulated, instant deposits and withdrawals. The ledger reference is derived from (account, Idempotency-Key),
 * so even a takeover of an abandoned request reaches portfolio-service with the same reference and cannot move
 * money twice.
 */
@Service
public class FundingService {

    public enum Direction { DEPOSIT, WITHDRAWAL }

    public record FundingResponse(UUID transactionId, Direction type, long amountCents, long cashBalanceCents) {
    }

    private final JdbcIdempotentExecutor idempotency;
    private final PortfolioLedgerClient ledger;
    private final AccountRepository accounts;
    private final ObjectMapper objectMapper;

    FundingService(JdbcIdempotentExecutor idempotency, PortfolioLedgerClient ledger, AccountRepository accounts,
                   ObjectMapper objectMapper) {
        this.idempotency = idempotency;
        this.ledger = ledger;
        this.accounts = accounts;
        this.objectMapper = objectMapper;
    }

    public StoredResponse move(Direction direction, UUID accountId, String idempotencyKey, String requestHash,
                               long amountCents) {
        return idempotency.execute(accountId, idempotencyKey, requestHash, () -> {
            Optional<AccountEntity> account = accounts.findById(accountId);
            if (account.isEmpty()) {
                return problem(HttpStatus.NOT_FOUND, "Account not found");
            }
            if (account.get().getStatus() != AccountStatus.ACTIVE) {
                return problem(HttpStatus.FORBIDDEN, "Account is locked");
            }
            UUID reference = UUID.nameUUIDFromBytes(
                    (direction + ":" + accountId + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
            try {
                LedgerResult result = direction == Direction.DEPOSIT
                        ? ledger.deposit(reference, accountId, amountCents)
                        : ledger.withdraw(reference, accountId, amountCents);
                return json(HttpStatus.CREATED,
                        new FundingResponse(reference, direction, amountCents, result.cashBalanceCents()));
            } catch (InsufficientFundsException e) {
                return problem(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
            }
        });
    }

    private StoredResponse problem(HttpStatus status, String detail) {
        return json(status, ProblemDetail.forStatusAndDetail(status, detail));
    }

    private StoredResponse json(HttpStatus status, Object body) {
        try {
            return new StoredResponse(status.value(), objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
