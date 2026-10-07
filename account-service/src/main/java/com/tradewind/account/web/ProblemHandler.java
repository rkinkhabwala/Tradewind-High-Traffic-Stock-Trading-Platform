package com.tradewind.account.web;

import com.tradewind.account.auth.AuthExceptions.AccountLockedException;
import com.tradewind.account.auth.AuthExceptions.EmailAlreadyRegisteredException;
import com.tradewind.account.auth.AuthExceptions.InvalidCredentialsException;
import com.tradewind.account.token.InvalidRefreshTokenException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** RFC 7807 responses for domain errors; framework errors (validation, 404, ...) come from the superclass. */
@RestControllerAdvice
class ProblemHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler({InvalidCredentialsException.class, InvalidRefreshTokenException.class})
    ProblemDetail unauthorized(RuntimeException e) {
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", e.getMessage());
    }

    @ExceptionHandler(AccountLockedException.class)
    ProblemDetail locked(AccountLockedException e) {
        return problem(HttpStatus.FORBIDDEN, "Account locked", e.getMessage());
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    ProblemDetail emailTaken(EmailAlreadyRegisteredException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage());
    }

    /** Two concurrent registrations of the same email: the unique constraint decides. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(DataIntegrityViolationException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", "Request conflicts with existing data");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
