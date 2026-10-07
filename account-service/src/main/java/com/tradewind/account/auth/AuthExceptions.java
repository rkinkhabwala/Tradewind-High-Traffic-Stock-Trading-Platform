package com.tradewind.account.auth;

public final class AuthExceptions {

    private AuthExceptions() {
    }

    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() {
            super("Email or password is incorrect");
        }
    }

    public static class EmailAlreadyRegisteredException extends RuntimeException {
        public EmailAlreadyRegisteredException() {
            super("Email is already registered");
        }
    }

    public static class AccountLockedException extends RuntimeException {
        public AccountLockedException() {
            super("Account is locked");
        }
    }
}
