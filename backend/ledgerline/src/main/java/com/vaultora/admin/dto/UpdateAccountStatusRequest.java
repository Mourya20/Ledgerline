package com.vaultora.admin.dto;

import com.vaultora.domain.AccountStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateAccountStatusRequest(
    @NotNull(message = "Account status is required")
    AccountStatus status
) {}
