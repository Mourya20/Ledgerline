package com.vaultora.audit;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vaultora.domain.TransactionStatus;
import com.vaultora.repository.AuditLogRepository;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditLogRepository auditLogRepository;
    private final AuditorInspectionService auditorInspectionService;

    public AuditController(AuditLogRepository auditLogRepository, AuditorInspectionService auditorInspectionService) {
        this.auditLogRepository = auditLogRepository;
        this.auditorInspectionService = auditorInspectionService;
    }

    @GetMapping("/logs")
    public Page<AuditLogResponse> listLogs(Pageable pageable) {
        return auditLogRepository.findAll(pageable)
                .map(AuditLogResponse::fromEntity);
    }

    @GetMapping("/transactions")
    public ResponseEntity<Page<AuditorTransactionResponse>> getTransactions(
            @RequestParam(required = false) Instant startDate,
            @RequestParam(required = false) Instant endDate,
            @RequestParam(required = false) String userEmail,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) TransactionStatus status,
            Pageable pageable) {
        return ResponseEntity.ok(auditorInspectionService.searchTransactions(
                startDate, endDate, userEmail, accountId, minAmount, maxAmount, type, status, pageable
        ));
    }

    @GetMapping(value = "/transactions/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportTransactionsCsv(
            @RequestParam(required = false) Instant startDate,
            @RequestParam(required = false) Instant endDate,
            @RequestParam(required = false) String userEmail,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) TransactionStatus status) {
        String csvContent = auditorInspectionService.exportTransactionsCsv(
                startDate, endDate, userEmail, accountId, minAmount, maxAmount, type, status
        );
        byte[] bytes = csvContent.getBytes(StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"vaultora-audit-transactions.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(bytes);
    }

    @GetMapping("/ledger-integrity")
    public ResponseEntity<LedgerIntegrityResponse> getLedgerIntegrity() {
        return ResponseEntity.ok(auditorInspectionService.verifyLedgerIntegrity());
    }
}
