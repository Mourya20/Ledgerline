package com.vaultora.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vaultora.audit.AuditService;
import com.vaultora.domain.AccountEntity;
import com.vaultora.domain.EntryType;
import com.vaultora.domain.LedgerEntryEntity;
import com.vaultora.domain.NormalBalance;
import com.vaultora.domain.UserEntity;
import com.vaultora.domain.UserRole;
import com.vaultora.exception.AccountAccessDeniedException;
import com.vaultora.exception.AccountNotFoundException;
import com.vaultora.repository.AccountRepository;
import com.vaultora.repository.LedgerEntryRepository;

@Service
public class StatementService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AuditService auditService;

    public StatementService(
            AccountRepository accountRepository,
            LedgerEntryRepository ledgerEntryRepository,
            AuditService auditService) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public byte[] generateStatement(
            UUID accountId,
            UserEntity actor,
            String format,
            Instant startDate,
            Instant endDate) {

        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

        if (actor.getRole() == UserRole.CUSTOMER) {
            if (account.getOwner() == null || !account.getOwner().getId().equals(actor.getId())) {
                throw new AccountAccessDeniedException("You do not have permission to view statements for this account");
            }
        }

        List<LedgerEntryEntity> allEntries = ledgerEntryRepository.findAllByAccountIdOrderByCreatedAtAsc(accountId);

        NormalBalance normalBalance = account.getNormalBalance();
        BigDecimal openingBalance = BigDecimal.ZERO;
        BigDecimal runningBalance = BigDecimal.ZERO;
        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;

        List<PdfStatementGenerator.StatementRow> rows = new ArrayList<>();

        for (LedgerEntryEntity entry : allEntries) {
            Instant entryTime = entry.getCreatedAt();
            boolean isBeforeStart = (startDate != null && entryTime.isBefore(startDate));
            boolean isAfterEnd = (endDate != null && entryTime.isAfter(endDate));

            BigDecimal debit = (entry.getEntryType() == EntryType.DEBIT) ? entry.getAmount() : BigDecimal.ZERO;
            BigDecimal credit = (entry.getEntryType() == EntryType.CREDIT) ? entry.getAmount() : BigDecimal.ZERO;

            if (normalBalance == NormalBalance.CREDIT) {
                runningBalance = runningBalance.add(credit).subtract(debit);
            } else {
                runningBalance = runningBalance.add(debit).subtract(credit);
            }

            if (isBeforeStart) {
                openingBalance = runningBalance;
            } else if (!isAfterEnd) {
                totalDebits = totalDebits.add(debit);
                totalCredits = totalCredits.add(credit);

                String txType = entry.getTransaction() != null ? entry.getTransaction().getTransactionType() : "TRANSACTION";
                String txId = entry.getTransaction() != null ? entry.getTransaction().getId().toString() : "";

                rows.add(new PdfStatementGenerator.StatementRow(
                        entryTime,
                        txId,
                        txType,
                        debit,
                        credit,
                        runningBalance
                ));
            }
        }

        BigDecimal closingBalance = runningBalance;

        auditService.recordEvent(
                actor,
                "ACCOUNT_STATEMENT_GENERATED",
                "ACCOUNT",
                account.getId(),
                Map.of(
                        "format", format != null ? format.toLowerCase() : "pdf",
                        "accountNumber", account.getAccountNumber(),
                        "entriesCount", String.valueOf(rows.size())
                )
        );

        if ("csv".equalsIgnoreCase(format)) {
            String csvText = CsvStatementGenerator.generate(
                    account, startDate, endDate, openingBalance, closingBalance, totalDebits, totalCredits, rows
            );
            return csvText.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        return PdfStatementGenerator.generate(
                account, startDate, endDate, openingBalance, closingBalance, totalDebits, totalCredits, rows
        );
    }
}
