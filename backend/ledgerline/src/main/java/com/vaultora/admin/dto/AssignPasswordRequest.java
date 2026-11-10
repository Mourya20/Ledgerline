package com.vaultora.admin.dto;

import com.vaultora.auth.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AssignPasswordRequest(
    @NotBlank(message = "Password cannot be blank")
    @Size(min = 12, message = "Password must be at least 12 characters")
    @MaxUtf8Bytes(max = 72, message = "Password must not exceed 72 UTF-8 bytes")
    String password
) {}
