package com.ledgerline.account.dto;

import jakarta.validation.constraints.Pattern;

public record CreateAccountRequest(
    @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO code")
    String currency
) {
    public CreateAccountRequest {
        if (currency == null || currency.isBlank()) {
            currency = "INR";
        }
    }
}
