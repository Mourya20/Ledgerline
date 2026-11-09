package com.vaultora.account.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.vaultora.domain.AccountEntity;
import com.vaultora.domain.AccountStatus;
import com.vaultora.domain.AccountType;

public record AccountResponse(
    UUID id,
    String accountNumber,
    AccountType accountType,
    String currency,
    AccountStatus status,
    BigDecimal balance,
    Instant createdAt,
    String ownerEmail,
    UUID ownerId
) {
    public AccountResponse(UUID id, String accountNumber, AccountType accountType, String currency, AccountStatus status, BigDecimal balance, Instant createdAt) {
        this(id, accountNumber, accountType, currency, status, balance, createdAt, null, null);
    }

    public AccountResponse(UUID id, String accountNumber, AccountType accountType, String currency, AccountStatus status, BigDecimal balance, Instant createdAt, String ownerEmail) {
        this(id, accountNumber, accountType, currency, status, balance, createdAt, ownerEmail, null);
    }

    public static AccountResponse fromEntity(AccountEntity entity) {
        return new AccountResponse(
            entity.getId(),
            entity.getAccountNumber(),
            entity.getAccountType(),
            entity.getCurrency(),
            entity.getStatus(),
            entity.getBalance(),
            entity.getCreatedAt(),
            entity.getOwner() != null ? entity.getOwner().getEmail() : null,
            entity.getOwner() != null ? entity.getOwner().getId() : null
        );
    }
}
