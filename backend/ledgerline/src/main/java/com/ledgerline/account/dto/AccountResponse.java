package com.ledgerline.account.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.ledgerline.domain.AccountEntity;
import com.ledgerline.domain.AccountStatus;
import com.ledgerline.domain.AccountType;

public record AccountResponse(
    UUID id,
    String accountNumber,
    AccountType accountType,
    String currency,
    AccountStatus status,
    BigDecimal balance,
    Instant createdAt
) {
    public static AccountResponse fromEntity(AccountEntity entity) {
        return new AccountResponse(
            entity.getId(),
            entity.getAccountNumber(),
            entity.getAccountType(),
            entity.getCurrency(),
            entity.getStatus(),
            entity.getBalance(),
            entity.getCreatedAt()
        );
    }
}
