package com.ledgerline.account.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountBalanceResponse(
    UUID accountId,
    String accountNumber,
    String currency,
    BigDecimal balance
) {
}
