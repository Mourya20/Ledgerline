package com.vaultora.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ResendVerificationRequest(
    @NotBlank(message = "Email is required")
    @Email(message = "Valid email is required")
    String email
) {
}
