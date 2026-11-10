package com.vaultora.account.dto;

import java.util.UUID;

import com.vaultora.domain.UserRole;
import jakarta.validation.constraints.Pattern;

public record CreateAccountRequest(
    @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO code")
    String currency,
    String customerEmail,
    UUID customerId,
    String customerPassword,
    UserRole role
) {
    public CreateAccountRequest(String currency) {
        this(currency, null, null, null, null);
    }

    public CreateAccountRequest(String currency, String customerEmail) {
        this(currency, customerEmail, null, null, null);
    }

    public CreateAccountRequest(String currency, String customerEmail, UUID customerId, String customerPassword) {
        this(currency, customerEmail, customerId, customerPassword, null);
    }

    public CreateAccountRequest {
        if (currency == null || currency.isBlank()) {
            currency = "INR";
        }
    }
}
