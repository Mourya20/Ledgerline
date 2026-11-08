package com.ledgerline.account;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerline.account.dto.AccountBalanceResponse;
import com.ledgerline.account.dto.AccountResponse;
import com.ledgerline.account.dto.CreateAccountRequest;
import com.ledgerline.audit.AuditService;
import com.ledgerline.domain.AccountEntity;
import com.ledgerline.domain.AccountType;
import com.ledgerline.domain.UserEntity;
import com.ledgerline.exception.AccountAccessDeniedException;
import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.exception.CurrencyMismatchException;
import com.ledgerline.exception.SystemAccountAccessException;
import com.ledgerline.repository.AccountRepository;

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
        String currency = request.currency();
        if (!SUPPORTED_CURRENCY.equalsIgnoreCase(currency)) {
            throw new CurrencyMismatchException("Only INR currency is currently supported");
        }

        String accountNumber = generateUniqueAccountNumber();
        AccountEntity account = new AccountEntity(accountNumber, user, AccountType.CUSTOMER, SUPPORTED_CURRENCY);
        AccountEntity saved = accountRepository.save(account);

        auditService.recordEvent(
                user,
                "ACCOUNT_CREATED",
                "ACCOUNT",
                saved.getId(),
                Map.of(
                        "accountNumber", saved.getAccountNumber(),
                        "currency", saved.getCurrency(),
                        "accountType", saved.getAccountType().name()
                )
        );

        return AccountResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getUserAccounts(UUID userId) {
        return accountRepository.findAllByOwnerId(userId)
                .stream()
                .map(AccountResponse::fromEntity)
                .toList();
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
