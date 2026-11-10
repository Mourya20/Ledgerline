package com.vaultora.account.dto;

import jakarta.validation.constraints.Size;

public record ReviewAccountRequest(
    @Size(max = 255, message = "Admin notes must not exceed 255 characters")
    String adminNotes
) {
}
