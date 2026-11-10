package com.vaultora.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.vaultora.domain.PasswordResetTokenEntity;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetTokenEntity, UUID> {
    Optional<PasswordResetTokenEntity> findByTokenHash(String tokenHash);
    void deleteAllByUserId(UUID userId);
}
