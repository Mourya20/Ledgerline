package com.vaultora.admin.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminTransferRequest(
    @NotNull(message = "Source account ID is required")
    UUID sourceAccountId,

    @NotBlank(message = "Destination account number is required")
    String destinationAccountNumber,

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
    BigDecimal amount,

    @Size(max = 255, message = "Description cannot exceed 255 characters")
    String description
) {
}
