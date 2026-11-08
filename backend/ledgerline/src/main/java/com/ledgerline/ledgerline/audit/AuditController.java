package com.ledgerline.ledgerline.audit;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerline.ledgerline.domain.AuditLogEntity;
import com.ledgerline.ledgerline.repository.AuditLogRepository;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditLogRepository auditLogRepository;

    public AuditController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping("/logs")
    public List<AuditLogEntity> listLogs() {
        return auditLogRepository.findAll();
    }

    @PostMapping("/logs")
    public ResponseEntity<Map<String, String>> adminOnlyAuditAction(@RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(Map.of("status", "admin-audit-action-recorded"));
    }
}
