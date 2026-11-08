package com.ledgerline.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ledgerline.domain.AccountEntity;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID>, AccountRepositoryCustom {

    List<AccountEntity> findAllByOwnerId(UUID ownerId);

    Optional<AccountEntity> findByAccountNumber(String accountNumber);
}
