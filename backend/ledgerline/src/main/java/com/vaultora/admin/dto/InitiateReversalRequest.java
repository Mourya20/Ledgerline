package com.vaultora.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InitiateReversalRequest(
    @NotBlank(message = "Reversal reason is required")
    @Size(max = 255, message = "Reason cannot exceed 255 characters")
    String reason
) {
}
