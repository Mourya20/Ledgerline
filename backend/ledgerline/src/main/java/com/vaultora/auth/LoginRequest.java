package com.vaultora.auth;

import jakarta.validation.constraints.NotBlank;

import com.vaultora.auth.validation.MaxUtf8Bytes;

public record LoginRequest(
        @NotBlank String email,
        @NotBlank @MaxUtf8Bytes(max = 72, message = "Password must not exceed 72 UTF-8 bytes") String password) {
}
