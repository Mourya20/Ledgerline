package com.vaultora.account;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vaultora.account.dto.AccountRequestResponse;
import com.vaultora.account.dto.AccountResponse;
import com.vaultora.account.dto.CreateAccountRequest;
import com.vaultora.audit.AuditService;
import com.vaultora.domain.AccountRequestEntity;
import com.vaultora.domain.AccountRequestStatus;
import com.vaultora.domain.UserEntity;
import com.vaultora.domain.UserRole;
import com.vaultora.exception.CurrencyMismatchException;
import com.vaultora.repository.AccountRequestRepository;

import jakarta.persistence.EntityManager;

@Service
public class AccountRequestService {

    private final AccountRequestRepository accountRequestRepository;
    private final AccountService accountService;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;

    public AccountRequestService(
            AccountRequestRepository accountRequestRepository,
            AccountService accountService,
            AuditService auditService,
            JdbcTemplate jdbcTemplate,
            EntityManager entityManager) {
        this.accountRequestRepository = accountRequestRepository;
        this.accountService = accountService;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
    }

    @Transactional
    public AccountRequestResponse createRequest(UserEntity user, String currency) {
        if (user.getRole() != UserRole.CUSTOMER) {
            throw new IllegalArgumentException("Only customers can submit account opening requests");
        }

        String targetCurrency = (currency != null && !currency.isBlank()) ? currency.toUpperCase() : "INR";
        if (!"INR".equalsIgnoreCase(targetCurrency)) {
            throw new CurrencyMismatchException("Only INR currency is currently supported for new accounts");
        }

        if (accountRequestRepository.existsByUserIdAndStatus(user.getId(), AccountRequestStatus.PENDING)) {
            throw new IllegalArgumentException("You already have a pending account opening request awaiting administrator approval");
        }

        AccountRequestEntity request = new AccountRequestEntity(user, targetCurrency);
        AccountRequestEntity saved;
        try {
            saved = accountRequestRepository.saveAndFlush(request);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("You already have a pending account opening request awaiting administrator approval");
        }

        Map<String, Object> details = new HashMap<>();
        details.put("requestId", saved.getId().toString());
        details.put("userEmail", user.getEmail());
        details.put("currency", targetCurrency);

        auditService.recordEvent(
                user,
                "ACCOUNT_REQUEST_CREATED",
                "ACCOUNT_REQUEST",
                saved.getId(),
                details
        );

        return AccountRequestResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public List<AccountRequestResponse> getUserRequests(UUID userId) {
        return accountRequestRepository.findAllByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(AccountRequestResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AccountRequestResponse> getAllRequests(AccountRequestStatus statusFilter) {
        List<AccountRequestEntity> requests = (statusFilter != null)
                ? accountRequestRepository.findAllByStatusOrderByCreatedAtDesc(statusFilter)
                : accountRequestRepository.findAllByOrderByCreatedAtDesc();

        return requests.stream()
                .map(AccountRequestResponse::fromEntity)
                .toList();
    }

    @Transactional
    public AccountRequestResponse reviewRequest(UUID requestId, boolean approve, String adminNotes, UserEntity admin) {
        AccountRequestEntity request = accountRequestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Account request not found: " + requestId));

        if (request.getStatus() != AccountRequestStatus.PENDING) {
            throw new IllegalArgumentException("Account request has already been reviewed with status: " + request.getStatus());
        }

        UUID createdAccountId = null;
        String newAccountNumber = null;

        if (approve) {
            AccountResponse createdAccount = accountService.createAccount(
                    admin,
                    request.getUser(),
                    new CreateAccountRequest(request.getCurrency())
            );
            createdAccountId = createdAccount.id();
            newAccountNumber = createdAccount.accountNumber();

            // Flush changes so that PostgreSQL sees the new account row for FK validation
            entityManager.flush();

            jdbcTemplate.update(
                    "CALL review_account_request(?, ?, ?, ?, ?)",
                    requestId,
                    AccountRequestStatus.APPROVED.name(),
                    adminNotes,
                    admin.getEmail(),
                    createdAccountId
            );

            Map<String, Object> details = new HashMap<>();
            details.put("requestId", requestId.toString());
            details.put("customerId", request.getUser().getId().toString());
            details.put("customerEmail", request.getUser().getEmail());
            details.put("accountNumber", newAccountNumber);
            details.put("accountId", createdAccountId.toString());
            details.put("reviewedBy", admin.getEmail());

            auditService.recordEvent(
                    admin,
                    "ACCOUNT_REQUEST_APPROVED",
                    "ACCOUNT_REQUEST",
                    requestId,
                    details
            );
        } else {
            entityManager.flush();

            jdbcTemplate.update(
                    "CALL review_account_request(?, ?, ?, ?, ?)",
                    requestId,
                    AccountRequestStatus.REJECTED.name(),
                    adminNotes,
                    admin.getEmail(),
                    null
            );

            Map<String, Object> details = new HashMap<>();
            details.put("requestId", requestId.toString());
            details.put("customerId", request.getUser().getId().toString());
            details.put("customerEmail", request.getUser().getEmail());
            details.put("reviewedBy", admin.getEmail());
            if (adminNotes != null) {
                details.put("adminNotes", adminNotes);
            }

            auditService.recordEvent(
                    admin,
                    "ACCOUNT_REQUEST_REJECTED",
                    "ACCOUNT_REQUEST",
                    requestId,
                    details
            );
        }

        // Clear persistence context so the refreshed entity reflects the stored procedure's updates
        entityManager.clear();

        AccountRequestEntity refreshed = accountRequestRepository.findById(requestId).orElseThrow();
        return AccountRequestResponse.fromEntity(refreshed);
    }
}
