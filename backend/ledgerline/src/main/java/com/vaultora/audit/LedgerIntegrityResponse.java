package com.vaultora.audit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record LedgerIntegrityResponse(
    String status,
    Instant verifiedAt,
    long transactionsChecked,
    long accountsChecked,
    BigDecimal totalDebits,
    BigDecimal totalCredits,
    boolean debitCreditEqual,
    int discrepanciesCount,
    List<String> discrepancies
) {
}
