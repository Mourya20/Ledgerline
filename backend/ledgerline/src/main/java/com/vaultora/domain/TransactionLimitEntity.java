package com.vaultora.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "transaction_limits")
public class TransactionLimitEntity {

    @Id
    @Column(length = 50)
    private String id; // e.g. "DEFAULT"

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "max_per_transaction", nullable = false, precision = 19, scale = 4)
    private BigDecimal maxPerTransaction;

    @Column(name = "max_daily_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal maxDailyTotal;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_user_id")
    private UserEntity updatedByUser;

    protected TransactionLimitEntity() {
    }

    public TransactionLimitEntity(String id, String currency, BigDecimal maxPerTransaction, BigDecimal maxDailyTotal, UserEntity updatedByUser) {
        this.id = id;
        this.currency = (currency != null) ? currency : "INR";
        this.maxPerTransaction = maxPerTransaction;
        this.maxDailyTotal = maxDailyTotal;
        this.updatedByUser = updatedByUser;
        this.updatedAt = Instant.now();
    }

    @PrePersist
    @PreUpdate
    void onSave() {
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getMaxPerTransaction() {
        return maxPerTransaction;
    }

    public void setMaxPerTransaction(BigDecimal maxPerTransaction) {
        this.maxPerTransaction = maxPerTransaction;
    }

    public BigDecimal getMaxDailyTotal() {
        return maxDailyTotal;
    }

    public void setMaxDailyTotal(BigDecimal maxDailyTotal) {
        this.maxDailyTotal = maxDailyTotal;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UserEntity getUpdatedByUser() {
        return updatedByUser;
    }

    public void setUpdatedByUser(UserEntity updatedByUser) {
        this.updatedByUser = updatedByUser;
    }
}
