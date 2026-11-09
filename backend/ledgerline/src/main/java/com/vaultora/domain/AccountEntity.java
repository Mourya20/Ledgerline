package com.vaultora.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "accounts")
public class AccountEntity {

    @Id
    private UUID id;

    @Column(name = "account_number", nullable = false, unique = true, length = 34, updatable = false)
    private String accountNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id", updatable = false)
    private UserEntity owner;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20, updatable = false)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "normal_balance", nullable = false, length = 6, updatable = false)
    private NormalBalance normalBalance;

    @Column(name = "allow_negative", nullable = false, updatable = false)
    private boolean allowNegative;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal balance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountEntity() {
    }

    public AccountEntity(String accountNumber, UserEntity owner, AccountType accountType, String currency) {
        this(accountNumber, owner, accountType,
                accountType == AccountType.CUSTOMER ? NormalBalance.CREDIT : NormalBalance.DEBIT,
                false, currency);
    }

    public AccountEntity(String accountNumber, UserEntity owner, AccountType accountType,
            NormalBalance normalBalance, boolean allowNegative, String currency) {
        this.id = UUID.randomUUID();
        this.accountNumber = accountNumber;
        this.owner = owner;
        this.accountType = accountType;
        this.normalBalance = normalBalance;
        this.allowNegative = allowNegative;
        this.currency = currency;
        this.status = AccountStatus.ACTIVE;
        this.balance = BigDecimal.ZERO.setScale(2);
    }

    @PrePersist
    void onCreate() {
        validateOwnership();
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        validateOwnership();
        updatedAt = Instant.now();
    }

    private void validateOwnership() {
        if (accountType == AccountType.CUSTOMER && owner == null) {
            throw new IllegalStateException("Customer accounts must have an owner");
        }
        if (accountType == AccountType.SYSTEM && owner != null) {
            throw new IllegalStateException("System accounts must not have a customer owner");
        }
    }

    public UUID getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public UserEntity getOwner() {
        return owner;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public NormalBalance getNormalBalance() {
        return normalBalance;
    }

    public boolean isAllowNegative() {
        return allowNegative;
    }

    public String getCurrency() {
        return currency;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
