package com.vaultora.admin.dto;

import jakarta.validation.constraints.Size;

public record ReviewMakerCheckerRequest(
    @Size(max = 255, message = "Reason cannot exceed 255 characters")
    String rejectionReason
) {
}
