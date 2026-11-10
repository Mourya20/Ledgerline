package com.vaultora.audit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vaultora.domain.AccountEntity;
import com.vaultora.domain.EntryType;
import com.vaultora.domain.LedgerEntryEntity;
import com.vaultora.domain.LedgerTransactionEntity;
import com.vaultora.domain.NormalBalance;
import com.vaultora.domain.TransactionStatus;
import com.vaultora.repository.AccountRepository;
import com.vaultora.repository.LedgerEntryRepository;
import com.vaultora.repository.LedgerTransactionRepository;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

@Service
public class AuditorInspectionService {

    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountRepository accountRepository;

    public AuditorInspectionService(
            LedgerTransactionRepository ledgerTransactionRepository,
            LedgerEntryRepository ledgerEntryRepository,
            AccountRepository accountRepository) {
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public Page<AuditorTransactionResponse> searchTransactions(
            Instant startDate,
            Instant endDate,
            String userEmail,
            UUID accountId,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            String type,
            TransactionStatus status,
            Pageable pageable) {

        Specification<LedgerTransactionEntity> spec = buildSpecification(
                startDate, endDate, userEmail, accountId, type, status
        );

        // Deterministic sort if not provided
        Pageable effectivePageable = (pageable != null && pageable.getSort().isSorted())
                ? pageable
                : PageRequest.of(
                        pageable != null ? pageable.getPageNumber() : 0,
                        pageable != null ? pageable.getPageSize() : 20,
                        Sort.by(Sort.Direction.DESC, "createdAt")
                );

        Page<LedgerTransactionEntity> page = ledgerTransactionRepository.findAll(spec, effectivePageable);

        if (page.isEmpty()) {
            return Page.empty(effectivePageable);
        }

        List<UUID> txIds = page.map(LedgerTransactionEntity::getId).toList();
        List<LedgerEntryEntity> allEntries = ledgerEntryRepository.findAllByTransactionIdIn(txIds);

        Map<UUID, List<LedgerEntryEntity>> entriesByTx = new HashMap<>();
        for (LedgerEntryEntity entry : allEntries) {
            entriesByTx.computeIfAbsent(entry.getTransaction().getId(), k -> new ArrayList<>()).add(entry);
        }

        List<AuditorTransactionResponse> responseList = new ArrayList<>();
        for (LedgerTransactionEntity tx : page.getContent()) {
            List<LedgerEntryEntity> entries = entriesByTx.getOrDefault(tx.getId(), Collections.emptyList());
            BigDecimal amount = calculateTransactionAmount(entries);
            String sourceAccount = resolveAccountRef(entries, EntryType.DEBIT);
            String destAccount = resolveAccountRef(entries, EntryType.CREDIT);

            // Min/max amount post-filtering if specified
            if (minAmount != null && amount.compareTo(minAmount) < 0) {
                continue;
            }
            if (maxAmount != null && amount.compareTo(maxAmount) > 0) {
                continue;
            }

            responseList.add(AuditorTransactionResponse.fromEntity(tx, amount, sourceAccount, destAccount));
        }

        return new PageImpl<>(responseList, effectivePageable, page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public String exportTransactionsCsv(
            Instant startDate,
            Instant endDate,
            String userEmail,
            UUID accountId,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            String type,
            TransactionStatus status) {

        Specification<LedgerTransactionEntity> spec = buildSpecification(
                startDate, endDate, userEmail, accountId, type, status
        );

        List<LedgerTransactionEntity> transactions = ledgerTransactionRepository.findAll(
                spec,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        List<UUID> txIds = transactions.stream().map(LedgerTransactionEntity::getId).toList();
        List<LedgerEntryEntity> allEntries = txIds.isEmpty()
                ? Collections.emptyList()
                : ledgerEntryRepository.findAllByTransactionIdIn(txIds);

        Map<UUID, List<LedgerEntryEntity>> entriesByTx = new HashMap<>();
        for (LedgerEntryEntity entry : allEntries) {
            entriesByTx.computeIfAbsent(entry.getTransaction().getId(), k -> new ArrayList<>()).add(entry);
        }

        StringBuilder csv = new StringBuilder();
        csv.append("Transaction ID,Type,Status,Currency,Amount,Initiated By,Source Account,Destination Account,Related Transaction ID,Reversal Reason,Created At,Completed At\r\n");

        for (LedgerTransactionEntity tx : transactions) {
            List<LedgerEntryEntity> entries = entriesByTx.getOrDefault(tx.getId(), Collections.emptyList());
            BigDecimal amount = calculateTransactionAmount(entries);

            if (minAmount != null && amount.compareTo(minAmount) < 0) {
                continue;
            }
            if (maxAmount != null && amount.compareTo(maxAmount) > 0) {
                continue;
            }

            String source = resolveAccountRef(entries, EntryType.DEBIT);
            String dest = resolveAccountRef(entries, EntryType.CREDIT);
            String user = tx.getInitiatedByUser() != null ? tx.getInitiatedByUser().getEmail() : "SYSTEM";
            String relatedId = tx.getRelatedTransaction() != null ? tx.getRelatedTransaction().getId().toString() : "";
            String reason = tx.getReversalReason() != null ? tx.getReversalReason() : "";

            csv.append(escapeCsvCell(tx.getId().toString())).append(",");
            csv.append(escapeCsvCell(tx.getTransactionType())).append(",");
            csv.append(escapeCsvCell(tx.getStatus().name())).append(",");
            csv.append(escapeCsvCell(tx.getCurrency())).append(",");
            csv.append(escapeCsvCell(amount.toPlainString())).append(",");
            csv.append(escapeCsvCell(user)).append(",");
            csv.append(escapeCsvCell(source)).append(",");
            csv.append(escapeCsvCell(dest)).append(",");
            csv.append(escapeCsvCell(relatedId)).append(",");
            csv.append(escapeCsvCell(reason)).append(",");
            csv.append(escapeCsvCell(tx.getCreatedAt() != null ? tx.getCreatedAt().toString() : "")).append(",");
            csv.append(escapeCsvCell(tx.getCompletedAt() != null ? tx.getCompletedAt().toString() : "")).append("\r\n");
        }

        return csv.toString();
    }

    @Transactional(readOnly = true)
    public LedgerIntegrityResponse verifyLedgerIntegrity() {
        BigDecimal totalDebits = ledgerEntryRepository.sumAllDebits();
        BigDecimal totalCredits = ledgerEntryRepository.sumAllCredits();
        boolean debitCreditEqual = totalDebits.compareTo(totalCredits) == 0;

        List<Object[]> unbalanced = ledgerEntryRepository.findUnbalancedTransactions();
        List<String> discrepancies = new ArrayList<>();

        if (!debitCreditEqual) {
            discrepancies.add(String.format("Global debit-credit imbalance: Total Debits (%s) != Total Credits (%s)", totalDebits, totalCredits));
        }

        for (Object[] row : unbalanced) {
            UUID txId = (UUID) row[0];
            BigDecimal debits = (BigDecimal) row[1];
            BigDecimal credits = (BigDecimal) row[2];
            discrepancies.add(String.format("Unbalanced transaction %s: Debits (%s) != Credits (%s)", txId, debits, credits));
        }

        List<AccountEntity> accounts = accountRepository.findAll();
        List<Object[]> entrySums = ledgerEntryRepository.sumAmountsByAccountAndEntryType();

        Map<UUID, Map<EntryType, BigDecimal>> accountSums = new HashMap<>();
        for (Object[] row : entrySums) {
            UUID accId = (UUID) row[0];
            EntryType type = (EntryType) row[1];
            BigDecimal sum = (BigDecimal) row[2];
            accountSums.computeIfAbsent(accId, k -> new HashMap<>()).put(type, sum);
        }

        long accountsChecked = accounts.size();
        for (AccountEntity acc : accounts) {
            Map<EntryType, BigDecimal> sums = accountSums.getOrDefault(acc.getId(), Collections.emptyMap());
            BigDecimal debits = sums.getOrDefault(EntryType.DEBIT, BigDecimal.ZERO);
            BigDecimal credits = sums.getOrDefault(EntryType.CREDIT, BigDecimal.ZERO);

            BigDecimal expectedBalance;
            if (acc.getNormalBalance() == NormalBalance.CREDIT) {
                expectedBalance = credits.subtract(debits);
            } else {
                expectedBalance = debits.subtract(credits);
            }

            if (acc.getBalance().compareTo(expectedBalance) != 0) {
                discrepancies.add(String.format("Account %s (%s) balance mismatch: Stored balance (%s) != Calculated balance (%s)",
                        acc.getAccountNumber(), acc.getAccountType(), acc.getBalance().toPlainString(), expectedBalance.toPlainString()));
            }
        }

        long txCount = ledgerTransactionRepository.count();
        boolean intact = debitCreditEqual && unbalanced.isEmpty() && discrepancies.isEmpty();

        return new LedgerIntegrityResponse(
                intact ? "INTACT" : "COMPROMISED",
                Instant.now(),
                txCount,
                accountsChecked,
                totalDebits,
                totalCredits,
                debitCreditEqual,
                discrepancies.size(),
                discrepancies
        );
    }

    private Specification<LedgerTransactionEntity> buildSpecification(
            Instant startDate,
            Instant endDate,
            String userEmail,
            UUID accountId,
            String type,
            TransactionStatus status) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startDate));
            }
            if (endDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), endDate));
            }
            if (type != null && !type.isBlank()) {
                predicates.add(cb.equal(cb.upper(root.get("transactionType")), type.toUpperCase().trim()));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (userEmail != null && !userEmail.isBlank()) {
                predicates.add(cb.like(cb.lower(root.join("initiatedByUser", JoinType.LEFT).get("email")),
                        "%" + userEmail.toLowerCase().trim() + "%"));
            }

            query.distinct(true);
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private BigDecimal calculateTransactionAmount(List<LedgerEntryEntity> entries) {
        if (entries == null || entries.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return entries.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .map(LedgerEntryEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String resolveAccountRef(List<LedgerEntryEntity> entries, EntryType targetType) {
        if (entries == null) return "";
        return entries.stream()
                .filter(e -> e.getEntryType() == targetType)
                .map(e -> e.getAccount().getAccountNumber())
                .findFirst()
                .orElse("");
    }

    public static String escapeCsvCell(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value.trim();
        // Formula injection defense: cells starting with =, +, -, @, tab, or carriage return
        if (sanitized.startsWith("=") || sanitized.startsWith("+") || sanitized.startsWith("-")
                || sanitized.startsWith("@") || sanitized.startsWith("\t") || sanitized.startsWith("\r")) {
            sanitized = "'" + sanitized;
        }
        if (sanitized.contains("\"") || sanitized.contains(",") || sanitized.contains("\n") || sanitized.contains("\r")) {
            sanitized = "\"" + sanitized.replace("\"", "\"\"") + "\"";
        }
        return sanitized;
    }
}
