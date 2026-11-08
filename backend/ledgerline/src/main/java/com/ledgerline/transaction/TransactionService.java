package com.ledgerline.transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerline.audit.AuditService;
import com.ledgerline.domain.AccountEntity;
import com.ledgerline.domain.AccountStatus;
import com.ledgerline.domain.AccountType;
import com.ledgerline.domain.EntryType;
import com.ledgerline.domain.LedgerEntryEntity;
import com.ledgerline.domain.LedgerTransactionEntity;
import com.ledgerline.domain.NormalBalance;
import com.ledgerline.domain.TransactionStatus;
import com.ledgerline.domain.UserEntity;
import com.ledgerline.exception.AccountAccessDeniedException;
import com.ledgerline.exception.AccountInactiveException;
import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.exception.CurrencyMismatchException;
import com.ledgerline.exception.IdempotencyConflictException;
import com.ledgerline.exception.InsufficientFundsException;
import com.ledgerline.exception.InvalidAmountException;
import com.ledgerline.exception.MissingIdempotencyKeyException;
import com.ledgerline.exception.SelfTransferException;
import com.ledgerline.exception.SystemAccountAccessException;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.LedgerEntryRepository;
import com.ledgerline.repository.LedgerTransactionRepository;
import com.ledgerline.transaction.dto.DepositRequest;
import com.ledgerline.transaction.dto.TransactionEntryResponse;
import com.ledgerline.transaction.dto.TransactionResponse;
import com.ledgerline.transaction.dto.TransferRequest;
import com.ledgerline.transaction.dto.WithdrawRequest;

import jakarta.persistence.EntityManager;

@Service
public class TransactionService {

    public static final String SUPPORTED_CURRENCY = "INR";

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AuditService auditService;
    private final EntityManager entityManager;

    public TransactionService(
            AccountRepository accountRepository,
            LedgerTransactionRepository ledgerTransactionRepository,
            LedgerEntryRepository ledgerEntryRepository,
            AuditService auditService,
            EntityManager entityManager) {
        this.accountRepository = accountRepository;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.auditService = auditService;
        this.entityManager = entityManager;
    }

    @Transactional
    public TransactionResponse deposit(UserEntity user, String idempotencyKey, DepositRequest request) {
        String normalizedKey = normalizeAndValidateIdempotencyKey(idempotencyKey);
        validateAmount(request.amount());
        validateCurrency(request.currency());

        BigDecimal normalizedAmount = request.amount().setScale(2, RoundingMode.UNNECESSARY);
        String normalizedCurrency = request.currency().toUpperCase(Locale.ROOT);
        String fingerprint = computeFingerprint(
                "DEPOSIT:" + request.accountId() + ":" + normalizedAmount.toPlainString() + ":" + normalizedCurrency
        );

        acquireIdempotencyAdvisoryLock(user.getId(), normalizedKey);

        Optional<TransactionResponse> existing = checkExistingIdempotentTransaction(user.getId(), normalizedKey, fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        AccountEntity customerAccount = resolveCustomerAccount(request.accountId(), user.getId());
        AccountEntity systemAccount = resolveSystemCounterpartAccount(normalizedCurrency);

        if (!customerAccount.getCurrency().equalsIgnoreCase(normalizedCurrency)) {
            throw new CurrencyMismatchException("Account currency does not match deposit currency");
        }
        if (!systemAccount.getCurrency().equalsIgnoreCase(normalizedCurrency)) {
            throw new CurrencyMismatchException("System counterpart currency does not match deposit currency");
        }

        // Deterministic locking of participating accounts in sorted UUID order
        lockAccountsInDeterministicOrder(customerAccount.getId(), systemAccount.getId());

        entityManager.refresh(customerAccount);
        entityManager.refresh(systemAccount);

        existing = checkExistingIdempotentTransaction(user.getId(), normalizedKey, fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        validateAccountActive(customerAccount);
        validateAccountActive(systemAccount);

        LedgerTransactionEntity tx = new LedgerTransactionEntity(
                "DEPOSIT",
                TransactionStatus.PENDING,
                user,
                SUPPORTED_CURRENCY,
                normalizedKey,
                fingerprint
        );
        tx = ledgerTransactionRepository.save(tx);

        // Double entry: DEBIT system cash asset (+asset), CREDIT customer liability (+liability)
        LedgerEntryEntity debitEntry = new LedgerEntryEntity(tx, systemAccount, EntryType.DEBIT, normalizedAmount, SUPPORTED_CURRENCY);
        LedgerEntryEntity creditEntry = new LedgerEntryEntity(tx, customerAccount, EntryType.CREDIT, normalizedAmount, SUPPORTED_CURRENCY);

        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        tx.setStatus(TransactionStatus.COMPLETED);
        tx = ledgerTransactionRepository.save(tx);

        entityManager.flush();
        entityManager.refresh(customerAccount);
        entityManager.refresh(systemAccount);

        auditService.recordEvent(
                user,
                "TRANSACTION_DEPOSIT",
                "LEDGER_TRANSACTION",
                tx.getId(),
                Map.of(
                        "accountId", customerAccount.getId().toString(),
                        "amount", normalizedAmount.toPlainString(),
                        "currency", SUPPORTED_CURRENCY,
                        "idempotencyKey", normalizedKey
                )
        );

        List<TransactionEntryResponse> entryResponses = List.of(
                TransactionEntryResponse.fromEntity(debitEntry, user.getId()),
                TransactionEntryResponse.fromEntity(creditEntry, user.getId())
        );
        return TransactionResponse.fromEntity(tx, entryResponses);
    }

    @Transactional
    public TransactionResponse withdraw(UserEntity user, String idempotencyKey, WithdrawRequest request) {
        String normalizedKey = normalizeAndValidateIdempotencyKey(idempotencyKey);
        validateAmount(request.amount());
        validateCurrency(request.currency());

        BigDecimal normalizedAmount = request.amount().setScale(2, RoundingMode.UNNECESSARY);
        String normalizedCurrency = request.currency().toUpperCase(Locale.ROOT);
        String fingerprint = computeFingerprint(
                "WITHDRAW:" + request.accountId() + ":" + normalizedAmount.toPlainString() + ":" + normalizedCurrency
        );

        acquireIdempotencyAdvisoryLock(user.getId(), normalizedKey);

        Optional<TransactionResponse> existing = checkExistingIdempotentTransaction(user.getId(), normalizedKey, fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        AccountEntity customerAccount = resolveCustomerAccount(request.accountId(), user.getId());
        AccountEntity systemAccount = resolveSystemCounterpartAccount(normalizedCurrency);

        if (!customerAccount.getCurrency().equalsIgnoreCase(normalizedCurrency)) {
            throw new CurrencyMismatchException("Account currency does not match withdrawal currency");
        }
        if (!systemAccount.getCurrency().equalsIgnoreCase(normalizedCurrency)) {
            throw new CurrencyMismatchException("System counterpart currency does not match withdrawal currency");
        }

        // Deterministic locking
        lockAccountsInDeterministicOrder(customerAccount.getId(), systemAccount.getId());

        entityManager.refresh(customerAccount);
        entityManager.refresh(systemAccount);

        existing = checkExistingIdempotentTransaction(user.getId(), normalizedKey, fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        validateAccountActive(customerAccount);
        validateAccountActive(systemAccount);

        // Balance validation occurs AFTER row locking
        if (customerAccount.getBalance().compareTo(normalizedAmount) < 0) {
            throw new InsufficientFundsException(
                    String.format("Insufficient funds in account %s. Available balance: %s, requested: %s",
                            customerAccount.getAccountNumber(), customerAccount.getBalance(), normalizedAmount)
            );
        }

        if (systemAccount.getBalance().compareTo(normalizedAmount) < 0) {
            throw new InsufficientFundsException(
                    "Insufficient system counterpart liquidity for withdrawal"
            );
        }

        LedgerTransactionEntity tx = new LedgerTransactionEntity(
                "WITHDRAWAL",
                TransactionStatus.PENDING,
                user,
                SUPPORTED_CURRENCY,
                normalizedKey,
                fingerprint
        );
        tx = ledgerTransactionRepository.save(tx);

        // Double entry: DEBIT customer liability (-liability), CREDIT system cash asset (-asset)
        LedgerEntryEntity debitEntry = new LedgerEntryEntity(tx, customerAccount, EntryType.DEBIT, normalizedAmount, SUPPORTED_CURRENCY);
        LedgerEntryEntity creditEntry = new LedgerEntryEntity(tx, systemAccount, EntryType.CREDIT, normalizedAmount, SUPPORTED_CURRENCY);

        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        tx.setStatus(TransactionStatus.COMPLETED);
        tx = ledgerTransactionRepository.save(tx);

        entityManager.flush();
        entityManager.refresh(customerAccount);
        entityManager.refresh(systemAccount);

        auditService.recordEvent(
                user,
                "TRANSACTION_WITHDRAW",
                "LEDGER_TRANSACTION",
                tx.getId(),
                Map.of(
                        "accountId", customerAccount.getId().toString(),
                        "amount", normalizedAmount.toPlainString(),
                        "currency", SUPPORTED_CURRENCY,
                        "idempotencyKey", normalizedKey
                )
        );

        List<TransactionEntryResponse> entryResponses = List.of(
                TransactionEntryResponse.fromEntity(debitEntry, user.getId()),
                TransactionEntryResponse.fromEntity(creditEntry, user.getId())
        );
        return TransactionResponse.fromEntity(tx, entryResponses);
    }

    @Transactional
    public TransactionResponse transfer(UserEntity user, String idempotencyKey, TransferRequest request) {
        String normalizedKey = normalizeAndValidateIdempotencyKey(idempotencyKey);
        validateAmount(request.amount());
        validateCurrency(request.currency());

        String destinationNumber = request.destinationAccountNumber() != null ? request.destinationAccountNumber().trim() : "";
        if (destinationNumber.isBlank()) {
            throw new IllegalArgumentException("Destination account number is required");
        }

        BigDecimal normalizedAmount = request.amount().setScale(2, RoundingMode.UNNECESSARY);
        String normalizedCurrency = request.currency().toUpperCase(Locale.ROOT);
        String fingerprint = computeFingerprint(
                "TRANSFER:" + request.sourceAccountId() + ":" + destinationNumber + ":"
                        + normalizedAmount.toPlainString() + ":" + normalizedCurrency
        );

        acquireIdempotencyAdvisoryLock(user.getId(), normalizedKey);

        Optional<TransactionResponse> existing = checkExistingIdempotentTransaction(user.getId(), normalizedKey, fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        AccountEntity sourceAccount = resolveCustomerAccount(request.sourceAccountId(), user.getId());

        AccountEntity destinationAccount = accountRepository.findByAccountNumber(destinationNumber)
                .orElseThrow(() -> new AccountNotFoundException("Destination account not found: " + destinationNumber));

        if (destinationAccount.getAccountType() != AccountType.CUSTOMER) {
            throw new SystemAccountAccessException("Transfers to system accounts are not permitted");
        }

        if (sourceAccount.getId().equals(destinationAccount.getId())) {
            throw new SelfTransferException("Transfers to the same account are not permitted");
        }

        if (!sourceAccount.getCurrency().equalsIgnoreCase(destinationAccount.getCurrency())) {
            throw new CurrencyMismatchException("Cross-currency transfers are not supported");
        }

        if (!sourceAccount.getCurrency().equalsIgnoreCase(normalizedCurrency)) {
            throw new CurrencyMismatchException("Source account currency does not match transfer currency");
        }

        // Deterministic locking: sort by UUID
        lockAccountsInDeterministicOrder(sourceAccount.getId(), destinationAccount.getId());

        entityManager.refresh(sourceAccount);
        entityManager.refresh(destinationAccount);

        existing = checkExistingIdempotentTransaction(user.getId(), normalizedKey, fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        validateAccountActive(sourceAccount);
        validateAccountActive(destinationAccount);

        // Validate sender balance after locking
        if (sourceAccount.getBalance().compareTo(normalizedAmount) < 0) {
            throw new InsufficientFundsException(
                    String.format("Insufficient funds in account %s. Available balance: %s, requested: %s",
                            sourceAccount.getAccountNumber(), sourceAccount.getBalance(), normalizedAmount)
            );
        }

        LedgerTransactionEntity tx = new LedgerTransactionEntity(
                "TRANSFER",
                TransactionStatus.PENDING,
                user,
                SUPPORTED_CURRENCY,
                normalizedKey,
                fingerprint
        );
        tx = ledgerTransactionRepository.save(tx);

        // Double entry: DEBIT sender (-liability), CREDIT receiver (+liability)
        LedgerEntryEntity debitEntry = new LedgerEntryEntity(tx, sourceAccount, EntryType.DEBIT, normalizedAmount, SUPPORTED_CURRENCY);
        LedgerEntryEntity creditEntry = new LedgerEntryEntity(tx, destinationAccount, EntryType.CREDIT, normalizedAmount, SUPPORTED_CURRENCY);

        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        tx.setStatus(TransactionStatus.COMPLETED);
        tx = ledgerTransactionRepository.save(tx);

        entityManager.flush();
        entityManager.refresh(sourceAccount);
        entityManager.refresh(destinationAccount);

        auditService.recordEvent(
                user,
                "TRANSACTION_TRANSFER",
                "LEDGER_TRANSACTION",
                tx.getId(),
                Map.of(
                        "sourceAccountId", sourceAccount.getId().toString(),
                        "destinationAccountId", destinationAccount.getId().toString(),
                        "amount", normalizedAmount.toPlainString(),
                        "currency", SUPPORTED_CURRENCY,
                        "idempotencyKey", normalizedKey
                )
        );

        List<TransactionEntryResponse> entryResponses = List.of(
                TransactionEntryResponse.fromEntity(debitEntry, user.getId()),
                TransactionEntryResponse.fromEntity(creditEntry, user.getId())
        );
        return TransactionResponse.fromEntity(tx, entryResponses);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> getTransactions(UUID userId, UUID accountId, Pageable pageable) {
        int pageSize = Math.min(pageable.getPageSize(), 100);
        Pageable cappedPageable = PageRequest.of(pageable.getPageNumber(), pageSize, pageable.getSort());

        Page<LedgerTransactionEntity> transactions;
        if (accountId != null) {
            AccountEntity account = accountRepository.findById(accountId)
                    .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));
            if (account.getOwner() == null || !account.getOwner().getId().equals(userId)) {
                throw new AccountAccessDeniedException("Access denied to account: " + accountId);
            }
            transactions = ledgerTransactionRepository.findByAccountIdAndOwnerId(accountId, userId, cappedPageable);
        } else {
            transactions = ledgerTransactionRepository.findUserInvolvedTransactions(userId, cappedPageable);
        }

        List<UUID> txIds = transactions.getContent().stream().map(LedgerTransactionEntity::getId).toList();
        Map<UUID, List<LedgerEntryEntity>> entriesByTxId = txIds.isEmpty()
                ? Collections.emptyMap()
                : ledgerEntryRepository.findAllByTransactionIdIn(txIds).stream()
                        .collect(Collectors.groupingBy(e -> e.getTransaction().getId()));

        return transactions.map(tx -> {
            List<TransactionEntryResponse> entries = entriesByTxId.getOrDefault(tx.getId(), Collections.emptyList()).stream()
                    .map(entry -> TransactionEntryResponse.fromEntity(entry, userId))
                    .toList();
            return TransactionResponse.fromEntity(tx, entries);
        });
    }

    @Transactional(readOnly = true)
    public TransactionResponse getTransactionById(UUID transactionId, UUID userId) {
        LedgerTransactionEntity tx = ledgerTransactionRepository.findById(transactionId)
                .orElseThrow(() -> new AccountNotFoundException("Transaction not found: " + transactionId));

        boolean userInvolved = (tx.getInitiatedByUser() != null && tx.getInitiatedByUser().getId().equals(userId))
                || ledgerTransactionRepository.isUserInvolvedInTransaction(transactionId, userId);

        if (!userInvolved) {
            throw new AccountAccessDeniedException("Access denied to transaction: " + transactionId);
        }

        List<TransactionEntryResponse> entries = ledgerEntryRepository.findAllByTransactionIdOrderByCreatedAtAsc(tx.getId())
                .stream()
                .map(e -> TransactionEntryResponse.fromEntity(e, userId))
                .toList();

        return TransactionResponse.fromEntity(tx, entries);
    }

    private void acquireIdempotencyAdvisoryLock(UUID userId, String normalizedKey) {
        long lockKey = computeAdvisoryLockKey(userId, normalizedKey);
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:lockKey)")
                .setParameter("lockKey", lockKey)
                .getResultList();
    }

    private long computeAdvisoryLockKey(UUID userId, String normalizedKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(userId.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(normalizedKey.getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            return ByteBuffer.wrap(hash).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    private void lockAccountsInDeterministicOrder(UUID accountId1, UUID accountId2) {
        List<UUID> sorted = Stream.of(accountId1, accountId2)
                .sorted(Comparator.naturalOrder())
                .toList();
        for (UUID id : sorted) {
            accountRepository.findByIdForUpdate(id);
        }
    }

    private Optional<TransactionResponse> checkExistingIdempotentTransaction(UUID userId, String key, String currentFingerprint) {
        Optional<LedgerTransactionEntity> existing = ledgerTransactionRepository.findByInitiatedByUserIdAndIdempotencyKey(userId, key);
        if (existing.isEmpty()) {
            return Optional.empty();
        }

        LedgerTransactionEntity tx = existing.get();
        if (!currentFingerprint.equalsIgnoreCase(tx.getRequestFingerprint())) {
            throw new IdempotencyConflictException(
                    String.format("Idempotency key '%s' was already used with a different request payload", key)
            );
        }

        if (tx.getStatus() == TransactionStatus.COMPLETED) {
            List<TransactionEntryResponse> entries = ledgerEntryRepository.findAllByTransactionIdOrderByCreatedAtAsc(tx.getId())
                    .stream()
                    .map(e -> TransactionEntryResponse.fromEntity(e, userId))
                    .toList();
            return Optional.of(TransactionResponse.fromEntity(tx, entries));
        }

        if (tx.getStatus() == TransactionStatus.PENDING) {
            throw new IdempotencyConflictException("A transaction with this idempotency key is currently pending");
        }

        if (tx.getStatus() == TransactionStatus.FAILED) {
            throw new IdempotencyConflictException("A transaction with this idempotency key previously failed and cannot be retried");
        }

        return Optional.empty();
    }

    private AccountEntity resolveCustomerAccount(UUID accountId, UUID userId) {
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

        if (account.getAccountType() != AccountType.CUSTOMER) {
            throw new SystemAccountAccessException("Customer operations are not permitted on system accounts");
        }

        if (account.getOwner() == null || !account.getOwner().getId().equals(userId)) {
            throw new AccountAccessDeniedException("Access denied to account: " + accountId);
        }

        return account;
    }

    private AccountEntity resolveSystemCounterpartAccount(String currency) {
        String normalizedCurrency = currency.toUpperCase(Locale.ROOT);
        if (!SUPPORTED_CURRENCY.equalsIgnoreCase(normalizedCurrency)) {
            throw new CurrencyMismatchException("Only " + SUPPORTED_CURRENCY + " currency is currently supported");
        }
        String vaultAccountNumber = "SYS-" + normalizedCurrency + "-CASH-VAULT-001";
        AccountEntity account = accountRepository.findByAccountNumber(vaultAccountNumber)
                .orElseThrow(() -> new IllegalStateException("System counterpart account not configured for: " + vaultAccountNumber));

        if (account.getAccountType() != AccountType.SYSTEM) {
            throw new IllegalStateException("Configured counterpart account is not a SYSTEM account: " + vaultAccountNumber);
        }
        if (account.getOwner() != null) {
            throw new IllegalStateException("Configured counterpart account must not have an owner: " + vaultAccountNumber);
        }
        if (!normalizedCurrency.equalsIgnoreCase(account.getCurrency())) {
            throw new IllegalStateException("Configured counterpart account currency mismatch: " + account.getCurrency());
        }
        if (account.getNormalBalance() != NormalBalance.DEBIT) {
            throw new IllegalStateException("Configured counterpart account must have DEBIT normal balance: " + vaultAccountNumber);
        }
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new IllegalStateException("Configured counterpart account is not ACTIVE: " + vaultAccountNumber);
        }
        if (account.isAllowNegative()) {
            throw new IllegalStateException("Configured counterpart account must have allow_negative = false: " + vaultAccountNumber);
        }

        return account;
    }

    private void validateAccountActive(AccountEntity account) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountInactiveException(
                    String.format("Account %s is inactive (status: %s)", account.getAccountNumber(), account.getStatus())
            );
        }
    }

    private String normalizeAndValidateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new MissingIdempotencyKeyException("Idempotency-Key header is required for financial transactions");
        }
        String trimmed = key.trim();
        if (trimmed.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must not exceed 255 characters");
        }
        return trimmed;
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Transaction amount must be strictly greater than zero");
        }
        if (amount.scale() > 2) {
            throw new InvalidAmountException("Transaction amount cannot have more than 2 decimal places");
        }
        if (amount.precision() - amount.scale() > 17) {
            throw new InvalidAmountException("Transaction amount exceeds maximum allowed numeric precision");
        }
    }

    private void validateCurrency(String currency) {
        if (currency == null || !SUPPORTED_CURRENCY.equalsIgnoreCase(currency.trim())) {
            throw new CurrencyMismatchException("Only " + SUPPORTED_CURRENCY + " currency is currently supported");
        }
    }

    public static String computeFingerprint(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(64);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
