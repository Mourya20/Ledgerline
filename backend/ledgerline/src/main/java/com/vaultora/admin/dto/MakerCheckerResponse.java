package com.vaultora.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.vaultora.domain.MakerCheckerRequestEntity;
import com.vaultora.domain.MakerCheckerStatus;
import com.vaultora.domain.MakerCheckerType;

public record MakerCheckerResponse(
    UUID id,
    MakerCheckerType requestType,
    MakerCheckerStatus status,
    String initiatedByEmail,
    String reviewedByEmail,
    Instant reviewedAt,
    String rejectionReason,
    BigDecimal amount,
    String currency,
    String sourceAccountNumber,
    String destinationAccountNumber,
    UUID originalTransactionId,
    UUID resultingTransactionId,
    String description,
    Instant createdAt
) {
    public static MakerCheckerResponse fromEntity(MakerCheckerRequestEntity entity) {
        return new MakerCheckerResponse(
            entity.getId(),
            entity.getRequestType(),
            entity.getStatus(),
            entity.getInitiatedByUser() != null ? entity.getInitiatedByUser().getEmail() : null,
            entity.getReviewedByUser() != null ? entity.getReviewedByUser().getEmail() : null,
            entity.getReviewedAt(),
            entity.getRejectionReason(),
            entity.getAmount(),
            entity.getCurrency(),
            entity.getSourceAccount() != null ? entity.getSourceAccount().getAccountNumber() : null,
            entity.getDestinationAccount() != null ? entity.getDestinationAccount().getAccountNumber() : null,
            entity.getOriginalTransaction() != null ? entity.getOriginalTransaction().getId() : null,
            entity.getResultingTransaction() != null ? entity.getResultingTransaction().getId() : null,
            entity.getDescription(),
            entity.getCreatedAt()
        );
    }
}
