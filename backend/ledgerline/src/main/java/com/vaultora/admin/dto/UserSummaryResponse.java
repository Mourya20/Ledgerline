package com.vaultora.admin.dto;

import java.time.Instant;
import java.util.UUID;

import com.vaultora.domain.UserRole;
import com.vaultora.domain.UserStatus;

public record UserSummaryResponse(
    UUID id,
    String email,
    UserRole role,
    UserStatus status,
    Instant createdAt,
    int accountCount
) {}
