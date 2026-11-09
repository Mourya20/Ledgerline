package com.vaultora.exception;

public class AccountAccessDeniedException extends RuntimeException {
    public AccountAccessDeniedException(String message) {
        super(message);
    }
}
