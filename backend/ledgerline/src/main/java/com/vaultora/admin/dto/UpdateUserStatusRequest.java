package com.vaultora.admin.dto;

import com.vaultora.domain.UserStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateUserStatusRequest(
    @NotNull(message = "User status is required")
    UserStatus status
) {}
