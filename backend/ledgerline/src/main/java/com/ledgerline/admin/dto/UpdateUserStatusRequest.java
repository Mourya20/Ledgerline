package com.ledgerline.admin.dto;

import com.ledgerline.domain.UserStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateUserStatusRequest(
    @NotNull(message = "User status is required")
    UserStatus status
) {}
