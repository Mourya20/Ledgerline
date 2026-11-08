package com.ledgerline.ledgerline.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ledgerline.ledgerline.domain.AuditLogEntity;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {

    List<AuditLogEntity> findAllByActionOrderByCreatedAtDesc(String action);

    long countByAction(String action);
}
