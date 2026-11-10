package com.vaultora.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vaultora.domain.AccountRequestEntity;
import com.vaultora.domain.AccountRequestStatus;

import jakarta.persistence.LockModeType;

@Repository
public interface AccountRequestRepository extends JpaRepository<AccountRequestEntity, UUID> {
    List<AccountRequestEntity> findAllByUserIdOrderByCreatedAtDesc(UUID userId);
    List<AccountRequestEntity> findAllByStatusOrderByCreatedAtDesc(AccountRequestStatus status);
    List<AccountRequestEntity> findAllByOrderByCreatedAtDesc();
    boolean existsByUserIdAndStatus(UUID userId, AccountRequestStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM AccountRequestEntity r WHERE r.id = :id")
    Optional<AccountRequestEntity> findByIdForUpdate(@Param("id") UUID id);
}
