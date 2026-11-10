package com.vaultora.account.dto;

import jakarta.validation.constraints.Pattern;

public record CreateAccountOpeningRequest(
    @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO code")
    String currency
) {
    public CreateAccountOpeningRequest {
        if (currency == null || currency.isBlank()) {
            currency = "INR";
        }
    }
}
