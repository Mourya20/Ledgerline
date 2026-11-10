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
import jakarta.persistence.Table;

@Entity
@Table(name = "maker_checker_requests")
public class MakerCheckerRequestEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 50)
    private MakerCheckerType requestType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MakerCheckerStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "initiated_by_user_id", nullable = false)
    private UserEntity initiatedByUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_user_id")
    private UserEntity reviewedByUser;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "rejection_reason", length = 255)
    private String rejectionReason;

    @Column(precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_account_id")
    private AccountEntity sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_account_id")
    private AccountEntity destinationAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_transaction_id")
    private LedgerTransactionEntity originalTransaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resulting_transaction_id")
    private LedgerTransactionEntity resultingTransaction;

    @Column(length = 255)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MakerCheckerRequestEntity() {
    }

    public MakerCheckerRequestEntity(
            MakerCheckerType requestType,
            UserEntity initiatedByUser,
            BigDecimal amount,
            String currency,
            AccountEntity sourceAccount,
            AccountEntity destinationAccount,
            LedgerTransactionEntity originalTransaction,
            String description) {
        this.id = UUID.randomUUID();
        this.requestType = requestType;
        this.status = MakerCheckerStatus.PENDING;
        this.initiatedByUser = initiatedByUser;
        this.amount = amount;
        this.currency = (currency != null) ? currency : "INR";
        this.sourceAccount = sourceAccount;
        this.destinationAccount = destinationAccount;
        this.originalTransaction = originalTransaction;
        this.description = description;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public MakerCheckerType getRequestType() {
        return requestType;
    }

    public MakerCheckerStatus getStatus() {
        return status;
    }

    public void setStatus(MakerCheckerStatus status) {
        this.status = status;
    }

    public UserEntity getInitiatedByUser() {
        return initiatedByUser;
    }

    public UserEntity getReviewedByUser() {
        return reviewedByUser;
    }

    public void setReviewedByUser(UserEntity reviewedByUser) {
        this.reviewedByUser = reviewedByUser;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public AccountEntity getSourceAccount() {
        return sourceAccount;
    }

    public AccountEntity getDestinationAccount() {
        return destinationAccount;
    }

    public LedgerTransactionEntity getOriginalTransaction() {
        return originalTransaction;
    }

    public LedgerTransactionEntity getResultingTransaction() {
        return resultingTransaction;
    }

    public void setResultingTransaction(LedgerTransactionEntity resultingTransaction) {
        this.resultingTransaction = resultingTransaction;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
