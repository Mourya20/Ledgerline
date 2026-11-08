package com.ledgerline.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ledgerline.domain.LedgerTransactionEntity;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransactionEntity, UUID> {

    Optional<LedgerTransactionEntity> findByInitiatedByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    @Query("SELECT DISTINCT t FROM LedgerTransactionEntity t " +
           "JOIN LedgerEntryEntity e ON e.transaction = t " +
           "WHERE e.account.owner.id = :userId " +
           "ORDER BY t.createdAt DESC")
    Page<LedgerTransactionEntity> findUserInvolvedTransactions(@Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT DISTINCT t FROM LedgerTransactionEntity t " +
           "JOIN LedgerEntryEntity e ON e.transaction = t " +
           "WHERE e.account.id = :accountId AND e.account.owner.id = :userId " +
           "ORDER BY t.createdAt DESC")
    Page<LedgerTransactionEntity> findByAccountIdAndOwnerId(@Param("accountId") UUID accountId, @Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT CASE WHEN COUNT(e) > 0 THEN true ELSE false END FROM LedgerEntryEntity e WHERE e.transaction.id = :transactionId AND e.account.owner.id = :userId")
    boolean isUserInvolvedInTransaction(@Param("transactionId") UUID transactionId, @Param("userId") UUID userId);
}
