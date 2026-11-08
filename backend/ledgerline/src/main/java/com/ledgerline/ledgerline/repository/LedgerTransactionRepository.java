package com.ledgerline.ledgerline.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ledgerline.ledgerline.domain.LedgerTransactionEntity;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransactionEntity, UUID> {

    Optional<LedgerTransactionEntity> findByInitiatedByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}
