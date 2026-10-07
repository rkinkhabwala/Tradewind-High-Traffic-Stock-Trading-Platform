package com.tradewind.portfolio.web;

import com.tradewind.portfolio.ledger.LedgerAccountType;
import com.tradewind.portfolio.ledger.LedgerExceptions.ConflictingReferenceException;
import com.tradewind.portfolio.ledger.LedgerExceptions.InsufficientBalanceException;
import com.tradewind.portfolio.ledger.LedgerExceptions.LedgerContentionException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class ProblemHandler extends ResponseEntityExceptionHandler {

    /** {@code code} lets order-service map the rejection without parsing text. */
    @ExceptionHandler(InsufficientBalanceException.class)
    ProblemDetail insufficient(InsufficientBalanceException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        problem.setTitle("Insufficient balance");
        boolean cash = e.account().type() == LedgerAccountType.CASH;
        problem.setProperty("code", cash ? "INSUFFICIENT_BUYING_POWER" : "INSUFFICIENT_SHARES");
        return problem;
    }

    @ExceptionHandler(ConflictingReferenceException.class)
    ProblemDetail conflict(ConflictingReferenceException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Conflict");
        return problem;
    }

    @ExceptionHandler(LedgerContentionException.class)
    ResponseEntity<ProblemDetail> busy(LedgerContentionException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setTitle("Busy");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1").body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Bad request");
        return problem;
    }
}
