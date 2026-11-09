package com.vaultora.transaction.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record TransferRequest(
    @NotNull(message = "sourceAccountId is required")
    UUID sourceAccountId,

    @NotBlank(message = "destinationAccountNumber is required")
    String destinationAccountNumber,

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be greater than zero")
    @Digits(integer = 17, fraction = 2, message = "amount must have at most 2 decimal places")
    BigDecimal amount,

    @NotNull(message = "currency is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO code")
    String currency
) {
}
