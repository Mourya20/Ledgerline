package com.ledgerline.ledgerline.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ledgerline.ledgerline.domain.AuditLogEntity;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {
}
