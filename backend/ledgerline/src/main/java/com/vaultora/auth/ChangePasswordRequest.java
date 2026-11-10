package com.vaultora.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.vaultora.auth.validation.MaxUtf8Bytes;

public record ChangePasswordRequest(
        @NotBlank(message = "Current password is required")
        String currentPassword,

        @NotBlank(message = "New password is required")
        @Size(min = 12, message = "New password must be at least 12 characters")
        @MaxUtf8Bytes(max = 72, message = "New password must not exceed 72 UTF-8 bytes")
        String newPassword
) {}
