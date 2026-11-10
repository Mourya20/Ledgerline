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

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntryEntity e WHERE e.entryType = com.vaultora.domain.EntryType.DEBIT")
    java.math.BigDecimal sumAllDebits();

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntryEntity e WHERE e.entryType = com.vaultora.domain.EntryType.CREDIT")
    java.math.BigDecimal sumAllCredits();

    @Query("SELECT e.account.id, e.entryType, SUM(e.amount) FROM LedgerEntryEntity e GROUP BY e.account.id, e.entryType")
    List<Object[]> sumAmountsByAccountAndEntryType();

    @Query("SELECT e.transaction.id, " +
           "SUM(CASE WHEN e.entryType = com.vaultora.domain.EntryType.DEBIT THEN e.amount ELSE 0 END), " +
           "SUM(CASE WHEN e.entryType = com.vaultora.domain.EntryType.CREDIT THEN e.amount ELSE 0 END) " +
           "FROM LedgerEntryEntity e " +
           "GROUP BY e.transaction.id " +
           "HAVING SUM(CASE WHEN e.entryType = com.vaultora.domain.EntryType.DEBIT THEN e.amount ELSE 0 END) <> " +
           "SUM(CASE WHEN e.entryType = com.vaultora.domain.EntryType.CREDIT THEN e.amount ELSE 0 END)")
    List<Object[]> findUnbalancedTransactions();

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntryEntity e WHERE e.account.id = :accountId AND e.entryType = com.vaultora.domain.EntryType.DEBIT AND e.createdAt >= :since")
    java.math.BigDecimal sumDailyDebitsForAccount(@Param("accountId") UUID accountId, @Param("since") java.time.Instant since);

    @Query("SELECT e FROM LedgerEntryEntity e JOIN FETCH e.transaction WHERE e.account.id = :accountId ORDER BY e.createdAt ASC")
    List<LedgerEntryEntity> findAllByAccountIdOrderByCreatedAtAsc(@Param("accountId") UUID accountId);
}
