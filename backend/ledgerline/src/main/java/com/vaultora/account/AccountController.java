package com.vaultora.account;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vaultora.account.dto.AccountBalanceResponse;
import com.vaultora.account.dto.AccountResponse;
import com.vaultora.account.dto.CreateAccountRequest;
import com.vaultora.domain.UserEntity;
import com.vaultora.domain.UserRole;
import com.vaultora.repository.UserRepository;

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
        UserEntity actor = resolveUser(authentication);
        CreateAccountRequest req = (request != null) ? request : new CreateAccountRequest("INR", null);

        UserEntity targetUser;
        if (actor.getRole() == UserRole.ADMIN) {
            if (req.customerEmail() == null || req.customerEmail().isBlank()) {
                throw new IllegalArgumentException("Administrator must specify customerEmail to assign the new account");
            }
            targetUser = userRepository.findByEmailIgnoreCase(req.customerEmail().trim())
                    .orElseThrow(() -> new IllegalArgumentException("Customer not found with email: " + req.customerEmail()));
            if (targetUser.getRole() != UserRole.CUSTOMER) {
                throw new IllegalArgumentException("Accounts can only be assigned to users with CUSTOMER role");
            }
        } else {
            targetUser = actor;
        }

        AccountResponse response = accountService.createAccount(actor, targetUser, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<AccountResponse>> getAccounts(
            Authentication authentication,
            @RequestParam(required = false) String customerEmail) {
        UserEntity actor = resolveUser(authentication);
        if (actor.getRole() == UserRole.ADMIN) {
            if (customerEmail != null && !customerEmail.isBlank()) {
                UserEntity target = userRepository.findByEmailIgnoreCase(customerEmail.trim())
                        .orElseThrow(() -> new IllegalArgumentException("Customer not found with email: " + customerEmail));
                return ResponseEntity.ok(accountService.getUserAccounts(target.getId()));
            }
            return ResponseEntity.ok(accountService.getAllCustomerAccounts());
        }
        return ResponseEntity.ok(accountService.getUserAccounts(actor.getId()));
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<AccountResponse> getAccount(
            Authentication authentication,
            @PathVariable UUID accountId) {
        UserEntity actor = resolveUser(authentication);
        if (actor.getRole() == UserRole.ADMIN) {
            return ResponseEntity.ok(accountService.getAccountByIdForAdmin(accountId));
        }
        return ResponseEntity.ok(accountService.getAccountById(accountId, actor.getId()));
    }

    @GetMapping("/{accountId}/balance")
    public ResponseEntity<AccountBalanceResponse> getAccountBalance(
            Authentication authentication,
            @PathVariable UUID accountId) {
        UserEntity actor = resolveUser(authentication);
        if (actor.getRole() == UserRole.ADMIN) {
            return ResponseEntity.ok(accountService.getAccountBalanceForAdmin(accountId));
        }
        return ResponseEntity.ok(accountService.getAccountBalance(accountId, actor.getId()));
    }

    private UserEntity resolveUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BadCredentialsException("Authentication required");
        }
        return userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new BadCredentialsException("Authenticated user not found"));
    }
}
