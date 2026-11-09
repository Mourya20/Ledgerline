package com.ledgerline.account.dto;

import jakarta.validation.constraints.Pattern;

public record CreateAccountRequest(
    @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO code")
    String currency,
    String customerEmail
) {
    public CreateAccountRequest(String currency) {
        this(currency, null);
    }

    public CreateAccountRequest {
        if (currency == null || currency.isBlank()) {
            currency = "INR";
        }
    }
}
