package com.ledgerline.auth;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.ledgerline.exception.AccountAccessDeniedException;
import com.ledgerline.exception.AccountInactiveException;
import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.exception.CurrencyMismatchException;
import com.ledgerline.exception.IdempotencyConflictException;
import com.ledgerline.exception.InsufficientFundsException;
import com.ledgerline.exception.InvalidAmountException;
import com.ledgerline.exception.MissingIdempotencyKeyException;
import com.ledgerline.exception.SelfTransferException;
import com.ledgerline.exception.SystemAccountAccessException;
import com.ledgerline.exception.TransactionNotFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<Map<String, String>> duplicateEmail() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Email is already registered"));
    }

    @ExceptionHandler({ BadCredentialsException.class, DisabledException.class })
    public ResponseEntity<Map<String, String>> invalidCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid credentials"));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, String>> rateLimitExceeded(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalidRequest() {
        return ResponseEntity.badRequest().body(Map.of("error", "Request validation failed"));
    }

    @ExceptionHandler({ AccountNotFoundException.class, TransactionNotFoundException.class })
    public ResponseEntity<Map<String, String>> resourceNotFound(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler({AccountAccessDeniedException.class, SystemAccountAccessException.class})
    public ResponseEntity<Map<String, String>> accessDenied(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<Map<String, String>> insufficientFunds(InsufficientFundsException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> idempotencyConflict(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> internalServerError(IllegalStateException ex) {
        logger.error("Internal server error encountered: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "An internal error occurred. Please try again later."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unhandledException(Exception ex) {
        logger.error("Unhandled exception encountered: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "An internal error occurred. Please try again later."));
    }

    @ExceptionHandler({
        AccountInactiveException.class,
        CurrencyMismatchException.class,
        InvalidAmountException.class,
        SelfTransferException.class,
        MissingIdempotencyKeyException.class,
        IllegalArgumentException.class
    })
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
