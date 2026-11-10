package com.vaultora.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.vaultora.domain.TransactionLimitEntity;

@Repository
public interface TransactionLimitRepository extends JpaRepository<TransactionLimitEntity, String> {
}
