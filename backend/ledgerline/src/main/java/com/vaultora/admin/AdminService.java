package com.vaultora.admin;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vaultora.account.dto.AccountResponse;
import com.vaultora.admin.dto.AdminStatsResponse;
import com.vaultora.admin.dto.UserSummaryResponse;
import com.vaultora.audit.AuditService;
import com.vaultora.domain.AccountEntity;
import com.vaultora.domain.AccountStatus;
import com.vaultora.domain.AccountType;
import com.vaultora.domain.UserEntity;
import com.vaultora.domain.UserRole;
import com.vaultora.domain.UserStatus;
import com.vaultora.repository.AccountRepository;
import com.vaultora.repository.LedgerTransactionRepository;
import com.vaultora.repository.UserRepository;

@Service
public class AdminService {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    public AdminService(
            UserRepository userRepository,
            AccountRepository accountRepository,
            LedgerTransactionRepository ledgerTransactionRepository,
            AuditService auditService,
            PasswordEncoder passwordEncoder,
            JdbcTemplate jdbcTemplate) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public List<UserSummaryResponse> getAllUsers() {
        List<UserEntity> users = userRepository.findAll();
        return users.stream().map(u -> {
            int accounts = accountRepository.findAllByOwnerId(u.getId()).size();
            return new UserSummaryResponse(
                    u.getId(),
                    u.getEmail(),
                    u.getRole(),
                    u.getStatus(),
                    u.getCreatedAt(),
                    accounts
            );
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getUserAccounts(UUID userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        return accountRepository.findAllByOwnerId(userId)
                .stream()
                .map(AccountResponse::fromEntity)
                .toList();
    }

    @Transactional
    public UserSummaryResponse updateUserStatus(UUID targetUserId, UserStatus newStatus, UserEntity actor) {
        if (actor.getId().equals(targetUserId)) {
            throw new IllegalArgumentException("Administrators cannot change their own active status");
        }
        UserEntity target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + targetUserId));

        UserStatus previousStatus = target.getStatus();
        target.setStatus(newStatus);
        UserEntity saved = userRepository.save(target);

        auditService.recordEvent(
                actor,
                "ADMIN_USER_STATUS_UPDATED",
                "USER",
                target.getId(),
                Map.of(
                        "email", target.getEmail(),
                        "previousStatus", previousStatus.name(),
                        "newStatus", newStatus.name()
                )
        );

        int accounts = accountRepository.findAllByOwnerId(saved.getId()).size();
        return new UserSummaryResponse(
                saved.getId(),
                saved.getEmail(),
                saved.getRole(),
                saved.getStatus(),
                saved.getCreatedAt(),
                accounts
        );
    }

    @Transactional
    public AccountEntity updateAccountStatus(UUID accountId, AccountStatus newStatus, UserEntity actor) {
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        if (account.getAccountType() != AccountType.CUSTOMER) {
            throw new IllegalArgumentException("Cannot modify status of system accounts");
        }

        AccountStatus previousStatus = account.getStatus();
        account.setStatus(newStatus);
        AccountEntity saved = accountRepository.save(account);

        auditService.recordEvent(
                actor,
                "ADMIN_ACCOUNT_STATUS_UPDATED",
                "ACCOUNT",
                account.getId(),
                Map.of(
                        "accountNumber", account.getAccountNumber(),
                        "previousStatus", previousStatus.name(),
                        "newStatus", newStatus.name(),
                        "ownerEmail", account.getOwner() != null ? account.getOwner().getEmail() : "none"
                )
        );

        return saved;
    }

    @Transactional
    public void assignUserPassword(UUID targetUserId, String newPassword, UserEntity actor) {
        UserEntity target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + targetUserId));

        if (target.getRole() != UserRole.CUSTOMER && target.getRole() != UserRole.AUDITOR) {
            throw new IllegalArgumentException("Administrators can only assign passwords for CUSTOMER and AUDITOR users");
        }

        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("Password cannot be blank");
        }
        if (newPassword.length() < 12) {
            throw new IllegalArgumentException("Password must be at least 12 characters");
        }
        if (newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Password must not exceed 72 UTF-8 bytes");
        }

        String encoded = passwordEncoder.encode(newPassword.trim());
        jdbcTemplate.update("CALL admin_assign_user_password(?, ?)", targetUserId, encoded);

        auditService.recordEvent(
                actor,
                "ADMIN_USER_PASSWORD_ASSIGNED",
                "USER",
                target.getId(),
                Map.of(
                        "email", target.getEmail(),
                        "role", target.getRole().name()
                )
        );
    }

    @Transactional(readOnly = true)
    public AdminStatsResponse getSystemStats() {
        long totalUsers = userRepository.count();
        long totalCustomers = userRepository.findAll().stream()
                .filter(u -> u.getRole() == UserRole.CUSTOMER)
                .count();
        List<AccountEntity> customerAccounts = accountRepository.findAllByAccountType(AccountType.CUSTOMER);
        long totalAccounts = customerAccounts.size();
        BigDecimal totalBalance = customerAccounts.stream()
                .map(AccountEntity::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long totalTransactions = ledgerTransactionRepository.count();

        return new AdminStatsResponse(totalUsers, totalCustomers, totalAccounts, totalBalance, totalTransactions);
    }
}
