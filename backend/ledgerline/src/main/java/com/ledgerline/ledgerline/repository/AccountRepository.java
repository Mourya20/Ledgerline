package com.ledgerline.ledgerline.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ledgerline.ledgerline.domain.AccountEntity;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

    List<AccountEntity> findAllByOwnerId(UUID ownerId);
}
