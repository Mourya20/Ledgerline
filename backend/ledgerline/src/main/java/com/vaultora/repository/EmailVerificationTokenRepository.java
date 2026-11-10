package com.vaultora.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.vaultora.domain.EmailVerificationTokenEntity;

@Repository
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationTokenEntity, UUID> {
    Optional<EmailVerificationTokenEntity> findByTokenHash(String tokenHash);
    void deleteAllByUserId(UUID userId);
}
