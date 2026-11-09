package com.vaultora.transaction.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.vaultora.domain.LedgerTransactionEntity;
import com.vaultora.domain.TransactionStatus;

public record TransactionResponse(
    UUID id,
    String transactionType,
    TransactionStatus status,
    String currency,
    List<TransactionEntryResponse> entries,
    Instant createdAt,
    Instant completedAt
) {
    public static TransactionResponse fromEntity(LedgerTransactionEntity entity, List<TransactionEntryResponse> entries) {
        return new TransactionResponse(
            entity.getId(),
            entity.getTransactionType(),
            entity.getStatus(),
            entity.getCurrency(),
            entries,
            entity.getCreatedAt(),
            entity.getCompletedAt()
        );
    }
}
