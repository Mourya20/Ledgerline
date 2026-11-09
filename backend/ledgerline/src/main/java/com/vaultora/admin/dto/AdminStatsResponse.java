package com.vaultora.admin.dto;

import java.math.BigDecimal;

public record AdminStatsResponse(
    long totalUsers,
    long totalCustomers,
    long totalAccounts,
    BigDecimal totalCustomerBalance,
    long totalTransactions
) {}
