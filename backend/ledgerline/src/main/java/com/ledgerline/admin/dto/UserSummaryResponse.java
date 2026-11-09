package com.ledgerline.admin.dto;

import java.time.Instant;
import java.util.UUID;

import com.ledgerline.domain.UserRole;
import com.ledgerline.domain.UserStatus;

public record UserSummaryResponse(
    UUID id,
    String email,
    UserRole role,
    UserStatus status,
    Instant createdAt,
    int accountCount
) {}
