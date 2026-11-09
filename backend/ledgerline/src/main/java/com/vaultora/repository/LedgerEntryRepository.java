package com.vaultora.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vaultora.domain.LedgerEntryEntity;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, UUID> {

    List<LedgerEntryEntity> findAllByTransactionId(UUID transactionId);

    List<LedgerEntryEntity> findAllByTransactionIdOrderByCreatedAtAsc(UUID transactionId);

    @Query("SELECT e FROM LedgerEntryEntity e JOIN FETCH e.account LEFT JOIN FETCH e.account.owner WHERE e.transaction.id IN :txIds ORDER BY e.createdAt ASC")
    List<LedgerEntryEntity> findAllByTransactionIdIn(@Param("txIds") Collection<UUID> txIds);
}
