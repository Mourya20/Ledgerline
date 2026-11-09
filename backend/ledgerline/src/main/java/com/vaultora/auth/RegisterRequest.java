package com.vaultora.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.vaultora.auth.validation.MaxUtf8Bytes;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 12) @MaxUtf8Bytes(max = 72, message = "Password must not exceed 72 UTF-8 bytes") String password) {
}
