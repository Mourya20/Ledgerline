package com.vaultora.audit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.vaultora.domain.LedgerTransactionEntity;
import com.vaultora.domain.TransactionStatus;

public record AuditorTransactionResponse(
    UUID id,
    String transactionType,
    TransactionStatus status,
    String currency,
    String initiatedByEmail,
    UUID relatedTransactionId,
    String reversalReason,
    Instant createdAt,
    Instant completedAt,
    BigDecimal amount,
    String sourceAccount,
    String destinationAccount
) {
    public static AuditorTransactionResponse fromEntity(
            LedgerTransactionEntity entity,
            BigDecimal amount,
            String sourceAccount,
            String destinationAccount) {
        return new AuditorTransactionResponse(
            entity.getId(),
            entity.getTransactionType(),
            entity.getStatus(),
            entity.getCurrency(),
            entity.getInitiatedByUser() != null ? entity.getInitiatedByUser().getEmail() : "SYSTEM",
            entity.getRelatedTransaction() != null ? entity.getRelatedTransaction().getId() : null,
            entity.getReversalReason(),
            entity.getCreatedAt(),
            entity.getCompletedAt(),
            amount,
            sourceAccount,
            destinationAccount
        );
    }
}
