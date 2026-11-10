package com.vaultora.admin.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record UpdateTransactionLimitRequest(
    @NotNull(message = "maxPerTransaction is required")
    @DecimalMin(value = "1.00", message = "maxPerTransaction must be at least 1.00")
    BigDecimal maxPerTransaction,

    @NotNull(message = "maxDailyTotal is required")
    @DecimalMin(value = "1.00", message = "maxDailyTotal must be at least 1.00")
    BigDecimal maxDailyTotal
) {
}
