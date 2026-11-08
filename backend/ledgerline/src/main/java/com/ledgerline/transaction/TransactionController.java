package com.ledgerline.transaction;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerline.domain.UserEntity;
import com.ledgerline.exception.MissingIdempotencyKeyException;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.transaction.dto.DepositRequest;
import com.ledgerline.transaction.dto.TransactionResponse;
import com.ledgerline.transaction.dto.TransferRequest;
import com.ledgerline.transaction.dto.WithdrawRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final TransactionService transactionService;
    private final UserRepository userRepository;

    public TransactionController(TransactionService transactionService, UserRepository userRepository) {
        this.transactionService = transactionService;
        this.userRepository = userRepository;
    }

    @PostMapping("/deposit")
    public ResponseEntity<TransactionResponse> deposit(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException("Idempotency-Key header is required for deposits");
        }
        UserEntity user = resolveUser(authentication);
        TransactionResponse response = transactionService.deposit(user, idempotencyKey.trim(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/withdraw")
    public ResponseEntity<TransactionResponse> withdraw(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody WithdrawRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException("Idempotency-Key header is required for withdrawals");
        }
        UserEntity user = resolveUser(authentication);
        TransactionResponse response = transactionService.withdraw(user, idempotencyKey.trim(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException("Idempotency-Key header is required for transfers");
        }
        UserEntity user = resolveUser(authentication);
        TransactionResponse response = transactionService.transfer(user, idempotencyKey.trim(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<Page<TransactionResponse>> getTransactions(
            Authentication authentication,
            @RequestParam(required = false) UUID accountId,
            Pageable pageable) {
        UserEntity user = resolveUser(authentication);
        return ResponseEntity.ok(transactionService.getTransactions(user.getId(), accountId, pageable));
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionResponse> getTransaction(
            Authentication authentication,
            @PathVariable UUID transactionId) {
        UserEntity user = resolveUser(authentication);
        return ResponseEntity.ok(transactionService.getTransactionById(transactionId, user.getId()));
    }

    private UserEntity resolveUser(Authentication authentication) {
        return userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new BadCredentialsException("Authenticated user no longer exists"));
    }
}
