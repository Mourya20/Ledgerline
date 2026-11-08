package com.ledgerline.account;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerline.account.dto.AccountBalanceResponse;
import com.ledgerline.account.dto.AccountResponse;
import com.ledgerline.account.dto.CreateAccountRequest;
import com.ledgerline.domain.UserEntity;
import com.ledgerline.repository.UserRepository;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;
    private final UserRepository userRepository;

    public AccountController(AccountService accountService, UserRepository userRepository) {
        this.accountService = accountService;
        this.userRepository = userRepository;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(
            Authentication authentication,
            @Valid @RequestBody(required = false) CreateAccountRequest request) {
        UserEntity user = resolveUser(authentication);
        CreateAccountRequest req = (request != null) ? request : new CreateAccountRequest("INR");
        AccountResponse response = accountService.createAccount(user, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<AccountResponse>> getAccounts(Authentication authentication) {
        UserEntity user = resolveUser(authentication);
        return ResponseEntity.ok(accountService.getUserAccounts(user.getId()));
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<AccountResponse> getAccount(
            Authentication authentication,
            @PathVariable UUID accountId) {
        UserEntity user = resolveUser(authentication);
        return ResponseEntity.ok(accountService.getAccountById(accountId, user.getId()));
    }

    @GetMapping("/{accountId}/balance")
    public ResponseEntity<AccountBalanceResponse> getAccountBalance(
            Authentication authentication,
            @PathVariable UUID accountId) {
        UserEntity user = resolveUser(authentication);
        return ResponseEntity.ok(accountService.getAccountBalance(accountId, user.getId()));
    }

    private UserEntity resolveUser(Authentication authentication) {
        return userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new BadCredentialsException("Authenticated user no longer exists"));
    }
}
