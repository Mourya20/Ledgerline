package com.vaultora.auth;

import jakarta.validation.constraints.NotBlank;

public record VerifyEmailRequest(
    @NotBlank(message = "Verification token is required")
    String token
) {
}
