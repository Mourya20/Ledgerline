package com.vaultora.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.vaultora.domain.TransactionLimitEntity;

public record TransactionLimitResponse(
    String id,
    String currency,
    BigDecimal maxPerTransaction,
    BigDecimal maxDailyTotal,
    Instant updatedAt,
    String updatedByEmail
) {
    public static TransactionLimitResponse fromEntity(TransactionLimitEntity entity) {
        return new TransactionLimitResponse(
            entity.getId(),
            entity.getCurrency(),
            entity.getMaxPerTransaction(),
            entity.getMaxDailyTotal(),
            entity.getUpdatedAt(),
            entity.getUpdatedByUser() != null ? entity.getUpdatedByUser().getEmail() : null
        );
    }
}
