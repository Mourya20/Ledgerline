package com.vaultora.admin;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public AdminService(
            UserRepository userRepository,
            AccountRepository accountRepository,
            LedgerTransactionRepository ledgerTransactionRepository,
            AuditService auditService) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.auditService = auditService;
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
