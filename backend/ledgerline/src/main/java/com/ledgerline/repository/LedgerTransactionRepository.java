package com.ledgerline.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ledgerline.domain.LedgerTransactionEntity;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransactionEntity, UUID> {

    Optional<LedgerTransactionEntity> findByInitiatedByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}
