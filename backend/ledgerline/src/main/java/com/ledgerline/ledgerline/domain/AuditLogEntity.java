package com.ledgerline.ledgerline.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_logs")
public class AuditLogEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private UserEntity actor;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(name = "resource_type", length = 100)
    private String resourceType;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String details = "{}";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLogEntity() {
    }

    public AuditLogEntity(UserEntity actor, String action, String resourceType, UUID resourceId, String details) {
        this.id = UUID.randomUUID();
        this.actor = actor;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.details = details == null ? "{}" : details;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UserEntity getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public String getDetails() {
        return details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
