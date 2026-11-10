package com.vaultora.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.vaultora.domain.AccountRequestEntity;
import com.vaultora.domain.AccountRequestStatus;

@Repository
public interface AccountRequestRepository extends JpaRepository<AccountRequestEntity, UUID> {
    List<AccountRequestEntity> findAllByUserIdOrderByCreatedAtDesc(UUID userId);
    List<AccountRequestEntity> findAllByStatusOrderByCreatedAtDesc(AccountRequestStatus status);
    List<AccountRequestEntity> findAllByOrderByCreatedAtDesc();
    boolean existsByUserIdAndStatus(UUID userId, AccountRequestStatus status);
}
