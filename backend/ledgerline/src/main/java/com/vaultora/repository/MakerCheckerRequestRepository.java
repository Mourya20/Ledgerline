package com.vaultora.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.vaultora.domain.MakerCheckerRequestEntity;
import com.vaultora.domain.MakerCheckerStatus;

import jakarta.persistence.LockModeType;

@Repository
public interface MakerCheckerRequestRepository extends JpaRepository<MakerCheckerRequestEntity, UUID> {

    List<MakerCheckerRequestEntity> findAllByStatusOrderByCreatedAtDesc(MakerCheckerStatus status);

    Page<MakerCheckerRequestEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM MakerCheckerRequestEntity r WHERE r.id = :id")
    Optional<MakerCheckerRequestEntity> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByOriginalTransactionIdAndStatus(UUID originalTransactionId, MakerCheckerStatus status);
}
