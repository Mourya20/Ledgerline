package com.vaultora.domain;

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
@Table(name = "ledger_transactions")
public class LedgerTransactionEntity {

    @Id
    private UUID id;

    @Column(name = "transaction_type", nullable = false, length = 30, updatable = false)
    private String transactionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "initiated_by_user_id", updatable = false)
    private UserEntity initiatedByUser;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "idempotency_key", length = 255, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", length = 64, updatable = false)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "related_transaction_id")
    private LedgerTransactionEntity relatedTransaction;

    @Column(name = "reversal_reason", length = 255)
    private String reversalReason;

    protected LedgerTransactionEntity() {
    }

    public LedgerTransactionEntity(String transactionType, TransactionStatus status, UserEntity initiatedByUser,
            String currency, String idempotencyKey, String requestFingerprint) {
        this(transactionType, status, initiatedByUser, currency, idempotencyKey, requestFingerprint, null, null);
    }

    public LedgerTransactionEntity(String transactionType, TransactionStatus status, UserEntity initiatedByUser,
            String currency, String idempotencyKey, String requestFingerprint,
            LedgerTransactionEntity relatedTransaction, String reversalReason) {
        this.id = UUID.randomUUID();
        this.transactionType = transactionType;
        this.status = status;
        this.initiatedByUser = initiatedByUser;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.relatedTransaction = relatedTransaction;
        this.reversalReason = reversalReason;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
        if (status == TransactionStatus.COMPLETED && this.completedAt == null) {
            this.completedAt = Instant.now();
        }
    }

    public UserEntity getInitiatedByUser() {
        return initiatedByUser;
    }

    public String getCurrency() {
        return currency;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public LedgerTransactionEntity getRelatedTransaction() {
        return relatedTransaction;
    }

    public void setRelatedTransaction(LedgerTransactionEntity relatedTransaction) {
        this.relatedTransaction = relatedTransaction;
    }

    public String getReversalReason() {
        return reversalReason;
    }

    public void setReversalReason(String reversalReason) {
        this.reversalReason = reversalReason;
    }
}
