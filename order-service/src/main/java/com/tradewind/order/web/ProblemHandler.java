package com.tradewind.order.web;

import com.tradewind.common.idempotency.IdempotencyConflictException;
import com.tradewind.common.idempotency.InvalidIdempotencyKeyException;
import com.tradewind.order.order.OrderService.OrderNotYetAcceptedException;
import com.tradewind.order.order.OrderValidator.NoPriceException;
import com.tradewind.order.portfolio.ReservationClient.ReservationUnavailableException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Errors thrown (rather than returned) are never recorded under the Idempotency-Key, so every 503/409 here is
 * safe to retry with the same key.
 */
@RestControllerAdvice
class ProblemHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(Identity.MissingIdentityException.class)
    ProblemDetail unauthorized(RuntimeException e) {
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", e.getMessage());
    }

    @ExceptionHandler({InvalidIdempotencyKeyException.class, IllegalArgumentException.class})
    ProblemDetail badRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.KeyReused.class)
    ProblemDetail keyReused(IdempotencyConflictException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key reused", e.getMessage());
    }

    @ExceptionHandler({IdempotencyConflictException.InProgress.class, OrderNotYetAcceptedException.class})
    ResponseEntity<ProblemDetail> conflictRetry(RuntimeException e) {
        return retryable(HttpStatus.CONFLICT, "Retry shortly", e.getMessage());
    }

    @ExceptionHandler(ReservationUnavailableException.class)
    ResponseEntity<ProblemDetail> reservationUnavailable(ReservationUnavailableException e) {
        return retryable(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable",
                "Could not reserve buying power right now; retry with the same Idempotency-Key");
    }

    @ExceptionHandler({NoPriceException.class, DataAccessResourceFailureException.class, QueryTimeoutException.class})
    ResponseEntity<ProblemDetail> dependencyUnavailable(RuntimeException e) {
        return retryable(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable", e.getMessage());
    }

    private static ResponseEntity<ProblemDetail> retryable(HttpStatus status, String title, String detail) {
        return ResponseEntity.status(status).header(HttpHeaders.RETRY_AFTER, "1").body(problem(status, title, detail));
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
