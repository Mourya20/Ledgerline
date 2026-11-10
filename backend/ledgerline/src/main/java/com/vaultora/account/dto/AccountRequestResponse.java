package com.vaultora.account.dto;

import java.time.Instant;
import java.util.UUID;

import com.vaultora.domain.AccountRequestEntity;
import com.vaultora.domain.AccountRequestStatus;

public record AccountRequestResponse(
    UUID id,
    UUID userId,
    String userEmail,
    String currency,
    AccountRequestStatus status,
    String adminNotes,
    String reviewedBy,
    UUID accountId,
    String accountNumber,
    Instant createdAt,
    Instant updatedAt
) {
    public static AccountRequestResponse fromEntity(AccountRequestEntity entity) {
        return new AccountRequestResponse(
            entity.getId(),
            entity.getUser() != null ? entity.getUser().getId() : null,
            entity.getUser() != null ? entity.getUser().getEmail() : null,
            entity.getCurrency(),
            entity.getStatus(),
            entity.getAdminNotes(),
            entity.getReviewedBy(),
            entity.getAccount() != null ? entity.getAccount().getId() : null,
            entity.getAccount() != null ? entity.getAccount().getAccountNumber() : null,
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
