package com.vaultora.account;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vaultora.account.dto.AccountBalanceResponse;
import com.vaultora.account.dto.AccountResponse;
import com.vaultora.account.dto.CreateAccountRequest;
import com.vaultora.audit.AuditService;
import com.vaultora.domain.AccountEntity;
import com.vaultora.domain.AccountStatus;
import com.vaultora.domain.AccountType;
import com.vaultora.domain.UserEntity;
import com.vaultora.domain.UserRole;
import com.vaultora.exception.AccountAccessDeniedException;
import com.vaultora.exception.AccountNotFoundException;
import com.vaultora.exception.CurrencyMismatchException;
import com.vaultora.exception.SystemAccountAccessException;
import com.vaultora.repository.AccountRepository;

@Service
public class AccountService {

    private static final String SUPPORTED_CURRENCY = "INR";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository accountRepository;
    private final AuditService auditService;

    public AccountService(AccountRepository accountRepository, AuditService auditService) {
        this.accountRepository = accountRepository;
        this.auditService = auditService;
    }

    @Transactional
    public AccountResponse createAccount(UserEntity user, CreateAccountRequest request) {
        return createAccount(user, user, request);
    }

    @Transactional
    public AccountResponse createAccount(UserEntity actor, UserEntity targetUser, CreateAccountRequest request) {
        String currency = request.currency();
        if (!SUPPORTED_CURRENCY.equalsIgnoreCase(currency)) {
            throw new CurrencyMismatchException("Only INR currency is currently supported");
        }

        String accountNumber = generateUniqueAccountNumber();
        AccountEntity account = new AccountEntity(accountNumber, targetUser, AccountType.CUSTOMER, SUPPORTED_CURRENCY);
        AccountEntity saved = accountRepository.save(account);

        Map<String, Object> details = new HashMap<>();
        details.put("accountNumber", saved.getAccountNumber());
        details.put("currency", saved.getCurrency());
        details.put("accountType", saved.getAccountType().name());
        details.put("assignedTo", targetUser.getEmail());
        if (!actor.getId().equals(targetUser.getId())) {
            details.put("createdByAdmin", actor.getEmail());
        }

        auditService.recordEvent(
                actor,
                "ACCOUNT_CREATED",
                "ACCOUNT",
                saved.getId(),
                details
        );

        return AccountResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getAllCustomerAccounts() {
        return accountRepository.findAllByAccountType(AccountType.CUSTOMER)
                .stream()
                .map(AccountResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getUserAccounts(UUID userId) {
        return accountRepository.findAllByOwnerId(userId)
                .stream()
                .map(AccountResponse::fromEntity)
                .toList();
    }

    @Transactional
    public AccountResponse closeAccount(UserEntity actor, UUID accountId) {
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

        if (account.getAccountType() == AccountType.SYSTEM) {
            throw new SystemAccountAccessException("System accounts cannot be closed or deleted");
        }

        if (actor.getRole() != UserRole.ADMIN) {
            if (account.getOwner() == null || !account.getOwner().getId().equals(actor.getId())) {
                throw new AccountAccessDeniedException("Access denied to account: " + accountId);
            }
        }

        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new IllegalArgumentException("Account is already closed");
        }

        if (account.getBalance().compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalArgumentException("Account balance must be 0.00 to close or delete an account. Current balance: "
                    + account.getBalance() + " " + account.getCurrency() + ". Please transfer or withdraw all funds first.");
        }

        AccountStatus previousStatus = account.getStatus();
        account.setStatus(AccountStatus.CLOSED);
        AccountEntity saved = accountRepository.save(account);

        Map<String, Object> details = new HashMap<>();
        details.put("accountNumber", saved.getAccountNumber());
        details.put("currency", saved.getCurrency());
        details.put("previousStatus", previousStatus.name());
        details.put("newStatus", AccountStatus.CLOSED.name());
        details.put("closedBy", actor.getEmail());
        details.put("actorRole", actor.getRole().name());
        if (saved.getOwner() != null) {
            details.put("ownerEmail", saved.getOwner().getEmail());
        }

        auditService.recordEvent(
                actor,
                "ACCOUNT_CLOSED",
                "ACCOUNT",
                saved.getId(),
                details
        );

        return AccountResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccountByIdForAdmin(UUID accountId) {
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));
        return AccountResponse.fromEntity(account);
    }

    @Transactional(readOnly = true)
    public AccountBalanceResponse getAccountBalanceForAdmin(UUID accountId) {
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));
        return new AccountBalanceResponse(account.getId(), account.getAccountNumber(), account.getCurrency(), account.getBalance());
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccountById(UUID accountId, UUID userId) {
        AccountEntity account = resolveCustomerAccount(accountId, userId);
        return AccountResponse.fromEntity(account);
    }

    @Transactional(readOnly = true)
    public AccountBalanceResponse getAccountBalance(UUID accountId, UUID userId) {
        AccountEntity account = resolveCustomerAccount(accountId, userId);
        return new AccountBalanceResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getCurrency(),
                account.getBalance()
        );
    }

    private AccountEntity resolveCustomerAccount(UUID accountId, UUID userId) {
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

        if (account.getAccountType() == AccountType.SYSTEM) {
            throw new SystemAccountAccessException("System accounts cannot be accessed via customer endpoints");
        }

        if (account.getOwner() == null || !account.getOwner().getId().equals(userId)) {
            throw new AccountAccessDeniedException("Access denied to account: " + accountId);
        }

        return account;
    }

    private String generateUniqueAccountNumber() {
        for (int i = 0; i < 10; i++) {
            long number = RANDOM.nextLong(100_000_000_000L, 1_000_000_000_000L);
            String candidate = "VLTR" + number;
            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Failed to generate unique account number after multiple attempts");
    }
}
