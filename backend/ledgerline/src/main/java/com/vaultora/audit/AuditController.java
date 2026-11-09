package com.vaultora.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vaultora.repository.AuditLogRepository;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditLogRepository auditLogRepository;

    public AuditController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping("/logs")
    public Page<AuditLogResponse> listLogs(Pageable pageable) {
        return auditLogRepository.findAll(pageable)
                .map(AuditLogResponse::fromEntity);
    }
}
