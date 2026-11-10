package com.vaultora.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vaultora.admin.dto.AdminTransferRequest;
import com.vaultora.admin.dto.MakerCheckerResponse;
import com.vaultora.audit.AuditService;
import com.vaultora.domain.AccountEntity;
import com.vaultora.domain.AccountType;
import com.vaultora.domain.EntryType;
import com.vaultora.domain.LedgerEntryEntity;
import com.vaultora.domain.LedgerTransactionEntity;
import com.vaultora.domain.MakerCheckerRequestEntity;
import com.vaultora.domain.MakerCheckerStatus;
import com.vaultora.domain.MakerCheckerType;
import com.vaultora.domain.NormalBalance;
import com.vaultora.domain.TransactionStatus;
import com.vaultora.domain.UserEntity;
import com.vaultora.exception.AccountInactiveException;
import com.vaultora.exception.AccountNotFoundException;
import com.vaultora.exception.CurrencyMismatchException;
import com.vaultora.exception.InsufficientFundsException;
import com.vaultora.exception.InvalidAmountException;
import com.vaultora.exception.SelfTransferException;
import com.vaultora.exception.SystemAccountAccessException;
import com.vaultora.exception.TransactionNotFoundException;
import com.vaultora.repository.AccountRepository;
import com.vaultora.repository.LedgerEntryRepository;
import com.vaultora.repository.LedgerTransactionRepository;
import com.vaultora.repository.MakerCheckerRequestRepository;
import com.vaultora.transaction.TransactionLimitService;

import jakarta.persistence.EntityManager;

@Service
public class MakerCheckerService {

    public static final String CURRENCY_INR = "INR";

    private final MakerCheckerRequestRepository makerCheckerRequestRepository;
    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountRepository accountRepository;
    private final TransactionLimitService transactionLimitService;
    private final AuditService auditService;
    private final EntityManager entityManager;

    @Value("${app.maker-checker.large-transfer-threshold:100000.00}")
    private BigDecimal largeTransferThreshold;

    public MakerCheckerService(
            MakerCheckerRequestRepository makerCheckerRequestRepository,
            LedgerTransactionRepository ledgerTransactionRepository,
            LedgerEntryRepository ledgerEntryRepository,
            AccountRepository accountRepository,
            TransactionLimitService transactionLimitService,
            AuditService auditService,
            EntityManager entityManager) {
        this.makerCheckerRequestRepository = makerCheckerRequestRepository;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.accountRepository = accountRepository;
        this.transactionLimitService = transactionLimitService;
        this.auditService = auditService;
        this.entityManager = entityManager;
    }

    public BigDecimal getLargeTransferThreshold() {
        return largeTransferThreshold;
    }

    @Transactional(readOnly = true)
    public List<MakerCheckerResponse> getRequests(MakerCheckerStatus status) {
        List<MakerCheckerRequestEntity> requests = (status != null)
                ? makerCheckerRequestRepository.findAllByStatusOrderByCreatedAtDesc(status)
                : makerCheckerRequestRepository.findAll();
        return requests.stream()
                .sorted(Comparator.comparing(MakerCheckerRequestEntity::getCreatedAt).reversed())
                .map(MakerCheckerResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public MakerCheckerResponse getRequestById(UUID requestId) {
        MakerCheckerRequestEntity entity = makerCheckerRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Maker-checker request not found: " + requestId));
        return MakerCheckerResponse.fromEntity(entity);
    }

    @Transactional
    public MakerCheckerResponse initiateReversal(UserEntity admin, UUID originalTransactionId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Reversal reason is required");
        }

        LedgerTransactionEntity originalTx = ledgerTransactionRepository.findById(originalTransactionId)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found: " + originalTransactionId));

        if (originalTx.getStatus() == TransactionStatus.REVERSED) {
            throw new IllegalArgumentException("Transaction has already been reversed");
        }
        if (originalTx.getStatus() != TransactionStatus.COMPLETED) {
            throw new IllegalArgumentException("Only COMPLETED transactions can be reversed");
        }
        if (originalTx.getRelatedTransaction() != null && "REVERSAL".equalsIgnoreCase(originalTx.getTransactionType())) {
            throw new IllegalArgumentException("Cannot reverse a reversal transaction");
        }

        if (makerCheckerRequestRepository.existsByOriginalTransactionIdAndStatus(originalTx.getId(), MakerCheckerStatus.PENDING)) {
            throw new IllegalArgumentException("A pending reversal request already exists for this transaction");
        }

        List<LedgerEntryEntity> entries = ledgerEntryRepository.findAllByTransactionId(originalTx.getId());
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Cannot reverse transaction with no ledger entries");
        }

        BigDecimal amount = entries.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .map(LedgerEntryEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        AccountEntity sourceAccount = null;
        AccountEntity destinationAccount = null;
        for (LedgerEntryEntity entry : entries) {
            if (entry.getEntryType() == EntryType.DEBIT) {
                sourceAccount = entry.getAccount();
            } else if (entry.getEntryType() == EntryType.CREDIT) {
                destinationAccount = entry.getAccount();
            }
        }

        MakerCheckerRequestEntity request = new MakerCheckerRequestEntity(
                MakerCheckerType.TRANSACTION_REVERSAL,
                admin,
                amount,
                originalTx.getCurrency(),
                sourceAccount,
                destinationAccount,
                originalTx,
                reason.trim()
        );

        MakerCheckerRequestEntity saved = makerCheckerRequestRepository.save(request);

        auditService.recordEvent(
                admin,
                "MAKER_CHECKER_REVERSAL_INITIATED",
                "MAKER_CHECKER",
                saved.getId(),
                Map.of(
                        "originalTransactionId", originalTx.getId().toString(),
                        "amount", amount.toPlainString(),
                        "currency", originalTx.getCurrency(),
                        "reason", reason.trim()
                )
        );

        return MakerCheckerResponse.fromEntity(saved);
    }

    @Transactional
    public MakerCheckerResponse initiateTransfer(UserEntity admin, AdminTransferRequest transferReq) {
        if (transferReq.amount() == null || transferReq.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Transfer amount must be strictly positive");
        }

        AccountEntity source = accountRepository.findById(transferReq.sourceAccountId())
                .orElseThrow(() -> new AccountNotFoundException("Source account not found"));

        AccountEntity destination = accountRepository.findByAccountNumber(transferReq.destinationAccountNumber().trim())
                .orElseThrow(() -> new AccountNotFoundException("Destination account not found: " + transferReq.destinationAccountNumber()));

        if (source.getId().equals(destination.getId())) {
            throw new SelfTransferException("Transfers to the same account are not permitted");
        }
        if (destination.getAccountType() != AccountType.CUSTOMER) {
            throw new SystemAccountAccessException("Transfers to system accounts are not permitted");
        }
        if (!source.getCurrency().equalsIgnoreCase(destination.getCurrency())) {
            throw new CurrencyMismatchException("Cross-currency transfers are not supported");
        }

        BigDecimal normalizedAmount = transferReq.amount().setScale(2, RoundingMode.UNNECESSARY);

        MakerCheckerRequestEntity request = new MakerCheckerRequestEntity(
                MakerCheckerType.LARGE_TRANSFER,
                admin,
                normalizedAmount,
                source.getCurrency(),
                source,
                destination,
                null,
                transferReq.description() != null ? transferReq.description().trim() : "Admin transfer"
        );

        MakerCheckerRequestEntity saved = makerCheckerRequestRepository.save(request);

        auditService.recordEvent(
                admin,
                "MAKER_CHECKER_TRANSFER_INITIATED",
                "MAKER_CHECKER",
                saved.getId(),
                Map.of(
                        "sourceAccountId", source.getId().toString(),
                        "destinationAccountNumber", destination.getAccountNumber(),
                        "amount", normalizedAmount.toPlainString(),
                        "currency", source.getCurrency()
                )
        );

        return MakerCheckerResponse.fromEntity(saved);
    }

    @Transactional
    public MakerCheckerResponse approveRequest(UUID requestId, UserEntity checker) {
        MakerCheckerRequestEntity request = makerCheckerRequestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Maker-checker request not found: " + requestId));

        if (request.getStatus() != MakerCheckerStatus.PENDING) {
            throw new IllegalArgumentException("Request is not in PENDING status (current status: " + request.getStatus() + ")");
        }

        if (checker.getId().equals(request.getInitiatedByUser().getId())) {
            throw new IllegalArgumentException("Initiator cannot approve their own request (maker-checker separation enforced)");
        }

        LedgerTransactionEntity resultingTx;
        if (request.getRequestType() == MakerCheckerType.TRANSACTION_REVERSAL) {
            resultingTx = executeReversal(request, checker);
        } else if (request.getRequestType() == MakerCheckerType.LARGE_TRANSFER) {
            resultingTx = executeTransfer(request, checker);
        } else {
            throw new IllegalStateException("Unsupported request type: " + request.getRequestType());
        }

        request.setStatus(MakerCheckerStatus.APPROVED);
        request.setReviewedByUser(checker);
        request.setReviewedAt(Instant.now());
        request.setResultingTransaction(resultingTx);

        MakerCheckerRequestEntity saved = makerCheckerRequestRepository.save(request);

        auditService.recordEvent(
                checker,
                "MAKER_CHECKER_APPROVED",
                "MAKER_CHECKER",
                saved.getId(),
                Map.of(
                        "requestType", saved.getRequestType().name(),
                        "initiatedBy", saved.getInitiatedByUser().getEmail(),
                        "reviewedBy", checker.getEmail(),
                        "resultingTransactionId", resultingTx != null ? resultingTx.getId().toString() : "none"
                )
        );

        return MakerCheckerResponse.fromEntity(saved);
    }

    @Transactional
    public MakerCheckerResponse rejectRequest(UUID requestId, UserEntity checker, String reason) {
        MakerCheckerRequestEntity request = makerCheckerRequestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Maker-checker request not found: " + requestId));

        if (request.getStatus() != MakerCheckerStatus.PENDING) {
            throw new IllegalArgumentException("Request is not in PENDING status (current status: " + request.getStatus() + ")");
        }

        if (checker.getId().equals(request.getInitiatedByUser().getId())) {
            throw new IllegalArgumentException("Initiator cannot reject their own request (maker-checker separation enforced)");
        }

        request.setStatus(MakerCheckerStatus.REJECTED);
        request.setReviewedByUser(checker);
        request.setReviewedAt(Instant.now());
        request.setRejectionReason(reason != null && !reason.isBlank() ? reason.trim() : "Rejected by checker");

        MakerCheckerRequestEntity saved = makerCheckerRequestRepository.save(request);

        auditService.recordEvent(
                checker,
                "MAKER_CHECKER_REJECTED",
                "MAKER_CHECKER",
                saved.getId(),
                Map.of(
                        "requestType", saved.getRequestType().name(),
                        "initiatedBy", saved.getInitiatedByUser().getEmail(),
                        "reviewedBy", checker.getEmail(),
                        "rejectionReason", request.getRejectionReason()
                )
        );

        return MakerCheckerResponse.fromEntity(saved);
    }

    private LedgerTransactionEntity executeReversal(MakerCheckerRequestEntity request, UserEntity checker) {
        LedgerTransactionEntity originalTx = request.getOriginalTransaction();
        if (originalTx == null) {
            throw new IllegalStateException("Original transaction not associated with reversal request");
        }

        // Revalidate original transaction state
        originalTx = ledgerTransactionRepository.findById(originalTx.getId())
                .orElseThrow(() -> new TransactionNotFoundException("Original transaction not found"));

        if (originalTx.getStatus() == TransactionStatus.REVERSED) {
            throw new IllegalArgumentException("Original transaction has already been reversed");
        }
        if (originalTx.getStatus() != TransactionStatus.COMPLETED) {
            throw new IllegalArgumentException("Only COMPLETED transactions can be reversed");
        }

        List<LedgerEntryEntity> originalEntries = ledgerEntryRepository.findAllByTransactionIdOrderByCreatedAtAsc(originalTx.getId());
        if (originalEntries.isEmpty()) {
            throw new IllegalStateException("No ledger entries found for original transaction: " + originalTx.getId());
        }

        // Lock all affected accounts in deterministic order
        List<UUID> accountIds = originalEntries.stream()
                .map(e -> e.getAccount().getId())
                .distinct()
                .sorted()
                .toList();

        for (UUID accId : accountIds) {
            accountRepository.findByIdForUpdate(accId)
                    .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accId));
        }

        // Recheck account active statuses and balances for accounts that will be debited
        for (LedgerEntryEntity orig : originalEntries) {
            AccountEntity acc = accountRepository.findById(orig.getAccount().getId()).orElseThrow();
            if (acc.getStatus() != com.vaultora.domain.AccountStatus.ACTIVE) {
                throw new AccountInactiveException("Account " + acc.getAccountNumber() + " is not ACTIVE for reversal");
            }

            // In reversal, the account that was originally CREDITED will now be DEBITED.
            // If it is a CUSTOMER liability account, a DEBIT decreases its balance.
            // Ensure it has sufficient funds!
            if (orig.getEntryType() == EntryType.CREDIT && acc.getAccountType() == AccountType.CUSTOMER) {
                if (acc.getBalance().compareTo(orig.getAmount()) < 0) {
                    throw new InsufficientFundsException(
                            String.format("Account %s has insufficient funds (%s) to reverse credit of %s",
                                    acc.getAccountNumber(), acc.getBalance().toPlainString(), orig.getAmount().toPlainString())
                    );
                }
            }
        }

        // Create compensating transaction
        String reversalKey = "REV-" + UUID.randomUUID();
        LedgerTransactionEntity compensatingTx = new LedgerTransactionEntity(
                "REVERSAL",
                TransactionStatus.PENDING,
                checker,
                originalTx.getCurrency(),
                reversalKey,
                computeSha256("REVERSAL_OF:" + originalTx.getId())
        );
        compensatingTx.setRelatedTransaction(originalTx);
        compensatingTx.setReversalReason(request.getDescription());
        compensatingTx = ledgerTransactionRepository.save(compensatingTx);

        // Create inverse entries
        for (LedgerEntryEntity orig : originalEntries) {
            AccountEntity acc = accountRepository.findById(orig.getAccount().getId()).orElseThrow();
            EntryType compensatingType = (orig.getEntryType() == EntryType.DEBIT) ? EntryType.CREDIT : EntryType.DEBIT;

            LedgerEntryEntity compensatingEntry = new LedgerEntryEntity(
                    compensatingTx,
                    acc,
                    compensatingType,
                    orig.getAmount(),
                    orig.getCurrency()
            );
            ledgerEntryRepository.save(compensatingEntry);
        }

        // Mark compensating transaction COMPLETED
        compensatingTx.setStatus(TransactionStatus.COMPLETED);
        compensatingTx = ledgerTransactionRepository.save(compensatingTx);

        // Transition original transaction to REVERSED with compensating references
        originalTx.setStatus(TransactionStatus.REVERSED);
        originalTx.setRelatedTransaction(compensatingTx);
        originalTx.setReversalReason(request.getDescription());
        ledgerTransactionRepository.save(originalTx);

        auditService.recordEvent(
                checker,
                "TRANSACTION_REVERSED",
                "LEDGER_TRANSACTION",
                originalTx.getId(),
                Map.of(
                        "compensatingTransactionId", compensatingTx.getId().toString(),
                        "reason", request.getDescription()
                )
        );

        return compensatingTx;
    }

    private LedgerTransactionEntity executeTransfer(MakerCheckerRequestEntity request, UserEntity checker) {
        AccountEntity source = request.getSourceAccount();
        AccountEntity dest = request.getDestinationAccount();

        // Lock accounts in deterministic order
        List<UUID> ids = java.util.stream.Stream.of(source.getId(), dest.getId()).sorted().toList();
        for (UUID id : ids) {
            accountRepository.findByIdForUpdate(id).orElseThrow();
        }

        source = accountRepository.findById(source.getId()).orElseThrow();
        dest = accountRepository.findById(dest.getId()).orElseThrow();

        if (source.getStatus() != com.vaultora.domain.AccountStatus.ACTIVE) {
            throw new AccountInactiveException("Source account is not ACTIVE");
        }
        if (dest.getStatus() != com.vaultora.domain.AccountStatus.ACTIVE) {
            throw new AccountInactiveException("Destination account is not ACTIVE");
        }

        transactionLimitService.validateTransactionLimits(source.getId(), request.getAmount(), "TRANSFER");

        if (source.getBalance().compareTo(request.getAmount()) < 0) {
            throw new InsufficientFundsException(
                    String.format("Insufficient funds in account %s. Available: %s, requested: %s",
                            source.getAccountNumber(), source.getBalance().toPlainString(), request.getAmount().toPlainString())
            );
        }

        String idempotencyKey = "MC-TRANSFER-" + request.getId();
        LedgerTransactionEntity tx = new LedgerTransactionEntity(
                "TRANSFER",
                TransactionStatus.PENDING,
                request.getInitiatedByUser(),
                request.getCurrency(),
                idempotencyKey,
                computeSha256("MAKER_CHECKER_TRANSFER:" + request.getId())
        );
        tx = ledgerTransactionRepository.save(tx);

        LedgerEntryEntity debitEntry = new LedgerEntryEntity(tx, source, EntryType.DEBIT, request.getAmount(), request.getCurrency());
        LedgerEntryEntity creditEntry = new LedgerEntryEntity(tx, dest, EntryType.CREDIT, request.getAmount(), request.getCurrency());

        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        tx.setStatus(TransactionStatus.COMPLETED);
        tx = ledgerTransactionRepository.save(tx);

        auditService.recordEvent(
                checker,
                "MAKER_CHECKER_TRANSFER_EXECUTED",
                "LEDGER_TRANSACTION",
                tx.getId(),
                Map.of(
                        "requestId", request.getId().toString(),
                        "amount", request.getAmount().toPlainString(),
                        "sourceAccount", source.getAccountNumber(),
                        "destinationAccount", dest.getAccountNumber()
                )
        );

        return tx;
    }

    private static String computeSha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm not available", ex);
        }
    }
}
