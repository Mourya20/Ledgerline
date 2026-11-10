package com.vaultora.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 320, updatable = false)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255, updatable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = true;

    protected UserEntity() {
    }

    public UserEntity(UUID id, String email, String passwordHash, UserRole role) {
        this(id, email, passwordHash, role, true);
    }

    public UserEntity(UUID id, String email, String passwordHash, UserRole role, boolean emailVerified) {
        this.id = (id != null) ? id : UUID.randomUUID();
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = UserStatus.ACTIVE;
        this.emailVerified = emailVerified;
    }

    public UserEntity(String email, String passwordHash, UserRole role) {
        this(UUID.randomUUID(), email, passwordHash, role, true);
    }

    public UserEntity(String email, String passwordHash, UserRole role, boolean emailVerified) {
        this(UUID.randomUUID(), email, passwordHash, role, emailVerified);
    }

    @PrePersist
    void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UserRole getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
