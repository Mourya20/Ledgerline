package com.ledgerline.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ledgerline.domain.AccountEntity;
import com.ledgerline.domain.AccountType;

import jakarta.persistence.LockModeType;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

    List<AccountEntity> findAllByOwnerId(UUID ownerId);

    Optional<AccountEntity> findByAccountNumber(String accountNumber);

    Optional<AccountEntity> findByIdAndOwnerId(UUID id, UUID ownerId);

    Optional<AccountEntity> findByAccountTypeAndCurrency(AccountType accountType, String currency);

    boolean existsByAccountNumber(String accountNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AccountEntity a WHERE a.id = :id")
    Optional<AccountEntity> findByIdForUpdate(@Param("id") UUID id);
}
