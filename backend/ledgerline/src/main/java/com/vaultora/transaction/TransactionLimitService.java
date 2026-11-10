package com.vaultora.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vaultora.admin.dto.TransactionLimitResponse;
import com.vaultora.admin.dto.UpdateTransactionLimitRequest;
import com.vaultora.audit.AuditService;
import com.vaultora.domain.TransactionLimitEntity;
import com.vaultora.domain.UserEntity;
import com.vaultora.exception.TransactionLimitExceededException;
import com.vaultora.repository.LedgerEntryRepository;
import com.vaultora.repository.TransactionLimitRepository;

@Service
public class TransactionLimitService {

    public static final String DEFAULT_LIMIT_ID = "DEFAULT";
    public static final String DEFAULT_CURRENCY = "INR";
    public static final BigDecimal FALLBACK_MAX_PER_TX = new BigDecimal("500000.0000");
    public static final BigDecimal FALLBACK_MAX_DAILY = new BigDecimal("2000000.0000");

    private final TransactionLimitRepository transactionLimitRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AuditService auditService;

    public TransactionLimitService(
            TransactionLimitRepository transactionLimitRepository,
            LedgerEntryRepository ledgerEntryRepository,
            AuditService auditService) {
        this.transactionLimitRepository = transactionLimitRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public TransactionLimitEntity getEffectiveLimits() {
        return transactionLimitRepository.findById(DEFAULT_LIMIT_ID)
                .orElse(new TransactionLimitEntity(DEFAULT_LIMIT_ID, DEFAULT_CURRENCY, FALLBACK_MAX_PER_TX, FALLBACK_MAX_DAILY, null));
    }

    @Transactional(readOnly = true)
    public TransactionLimitResponse getLimitsResponse() {
        return TransactionLimitResponse.fromEntity(getEffectiveLimits());
    }

    @Transactional
    public TransactionLimitResponse updateLimits(UserEntity admin, UpdateTransactionLimitRequest request) {
        if (request.maxDailyTotal().compareTo(request.maxPerTransaction()) < 0) {
            throw new IllegalArgumentException("Maximum daily limit cannot be less than maximum per-transaction limit");
        }

        TransactionLimitEntity limit = transactionLimitRepository.findById(DEFAULT_LIMIT_ID)
                .orElseGet(() -> new TransactionLimitEntity(DEFAULT_LIMIT_ID, DEFAULT_CURRENCY, request.maxPerTransaction(), request.maxDailyTotal(), admin));

        limit.setMaxPerTransaction(request.maxPerTransaction());
        limit.setMaxDailyTotal(request.maxDailyTotal());
        limit.setUpdatedByUser(admin);

        TransactionLimitEntity saved = transactionLimitRepository.save(limit);

        auditService.recordEvent(
                admin,
                "TRANSACTION_LIMITS_UPDATED",
                "SETTINGS",
                null,
                Map.of(
                        "maxPerTransaction", saved.getMaxPerTransaction().toPlainString(),
                        "maxDailyTotal", saved.getMaxDailyTotal().toPlainString(),
                        "currency", saved.getCurrency()
                )
        );

        return TransactionLimitResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public void validateTransactionLimits(UUID accountId, BigDecimal amount, String transactionType) {
        TransactionLimitEntity limits = getEffectiveLimits();

        if (amount.compareTo(limits.getMaxPerTransaction()) > 0) {
            throw new TransactionLimitExceededException(
                    String.format("Amount %s exceeds maximum per-transaction limit of %s %s",
                            amount.toPlainString(), limits.getMaxPerTransaction().toPlainString(), limits.getCurrency())
            );
        }

        Instant startOfDay = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).toInstant();
        BigDecimal dailyDebits = ledgerEntryRepository.sumDailyDebitsForAccount(accountId, startOfDay);

        if (dailyDebits.add(amount).compareTo(limits.getMaxDailyTotal()) > 0) {
            throw new TransactionLimitExceededException(
                    String.format("Transaction would exceed daily aggregate limit of %s %s (already used today: %s %s, requested: %s %s)",
                            limits.getMaxDailyTotal().toPlainString(), limits.getCurrency(),
                            dailyDebits.toPlainString(), limits.getCurrency(),
                            amount.toPlainString(), limits.getCurrency())
            );
        }
    }
}
