package com.vaultora.transaction.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.vaultora.domain.AccountType;
import com.vaultora.domain.EntryType;
import com.vaultora.domain.LedgerEntryEntity;

public record TransactionEntryResponse(
    UUID id,
    UUID accountId,
    String accountNumber,
    EntryType entryType,
    BigDecimal amount,
    String currency
) {
    public static final String SYSTEM_COUNTERPART_LABEL = "Cash/Bank Counterpart";

    public static TransactionEntryResponse fromEntity(LedgerEntryEntity entity) {
        return fromEntity(entity, null);
    }

    public static TransactionEntryResponse fromEntityForAdmin(LedgerEntryEntity entity) {
        var account = entity.getAccount();
        if (account == null || account.getAccountType() == AccountType.SYSTEM) {
            return new TransactionEntryResponse(
                entity.getId(),
                null,
                SYSTEM_COUNTERPART_LABEL,
                entity.getEntryType(),
                entity.getAmount(),
                entity.getCurrency()
            );
        }
        return new TransactionEntryResponse(
            entity.getId(),
            account.getId(),
            account.getAccountNumber(),
            entity.getEntryType(),
            entity.getAmount(),
            entity.getCurrency()
        );
    }

    public static TransactionEntryResponse fromEntity(LedgerEntryEntity entity, UUID viewerUserId) {
        var account = entity.getAccount();
        if (account == null || account.getAccountType() == AccountType.SYSTEM) {
            return new TransactionEntryResponse(
                entity.getId(),
                null,
                SYSTEM_COUNTERPART_LABEL,
                entity.getEntryType(),
                entity.getAmount(),
                entity.getCurrency()
            );
        }

        boolean isViewerAccount = viewerUserId != null
                && account.getOwner() != null
                && viewerUserId.equals(account.getOwner().getId());

        if (isViewerAccount) {
            return new TransactionEntryResponse(
                entity.getId(),
                account.getId(),
                account.getAccountNumber(),
                entity.getEntryType(),
                entity.getAmount(),
                entity.getCurrency()
            );
        }

        String rawNumber = account.getAccountNumber();
        String maskedNumber = maskAccountNumber(rawNumber);
        return new TransactionEntryResponse(
            entity.getId(),
            null,
            maskedNumber,
            entity.getEntryType(),
            entity.getAmount(),
            entity.getCurrency()
        );
    }

    private static String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return "****";
        }
        if (accountNumber.length() <= 4) {
            return "****" + accountNumber;
        }
        return "****" + accountNumber.substring(accountNumber.length() - 4);
    }
}
