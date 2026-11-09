package com.ledgerline.admin.dto;

import com.ledgerline.domain.AccountStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateAccountStatusRequest(
    @NotNull(message = "Account status is required")
    AccountStatus status
) {}
