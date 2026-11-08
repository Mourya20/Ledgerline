package com.ledgerline.audit;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerline.domain.AuditLogEntity;
import com.ledgerline.domain.UserEntity;
import com.ledgerline.repository.AuditLogRepository;

@Service
public class AuditService {

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "password_hash", "token", "secret", "session", "sessionid",
            "xsrf", "csrf", "cookie", "authorization", "credentials"
    );

    public static final String ACTION_REGISTER_SUCCESS = "AUTH_REGISTER_SUCCESS";
    public static final String ACTION_LOGIN_SUCCESS = "AUTH_LOGIN_SUCCESS";
    public static final String ACTION_LOGIN_FAILURE = "AUTH_LOGIN_FAILURE";
    public static final String ACTION_LOGOUT = "AUTH_LOGOUT";
    public static final String ACTION_DISABLED_USER_ATTEMPT = "AUTH_DISABLED_USER_ATTEMPT";
    public static final String ACTION_RATE_LIMIT_EXCEEDED = "AUTH_RATE_LIMIT_EXCEEDED";

    public static final String ACTION_ACCOUNT_CREATED = "ACCOUNT_CREATED";
    public static final String ACTION_TRANSACTION_DEPOSIT = "TRANSACTION_DEPOSIT";
    public static final String ACTION_TRANSACTION_WITHDRAW = "TRANSACTION_WITHDRAW";
    public static final String ACTION_TRANSACTION_TRANSFER = "TRANSACTION_TRANSFER";

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public AuditLogEntity recordEvent(UserEntity actor, String action, String resourceType, UUID resourceId,
            Map<String, ?> metadata) {
        return persistLog(actor, action, resourceType, resourceId, metadata);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLogEntity recordEventRequiresNew(UserEntity actor, String action, String resourceType, UUID resourceId,
            Map<String, ?> metadata) {
        return persistLog(actor, action, resourceType, resourceId, metadata);
    }

    private AuditLogEntity persistLog(UserEntity actor, String action, String resourceType, UUID resourceId,
            Map<String, ?> metadata) {
        Map<String, Object> sanitized = sanitizeMetadata(metadata);
        AuditLogEntity entity = new AuditLogEntity(actor, action, resourceType, resourceId, sanitized);
        return auditLogRepository.save(entity);
    }

    private Map<String, Object> sanitizeMetadata(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, Object> sanitized = new HashMap<>();
        for (Map.Entry<String, ?> entry : metadata.entrySet()) {
            String key = entry.getKey();
            if (key != null && !SENSITIVE_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                sanitized.put(key, entry.getValue());
            }
        }
        return sanitized;
    }
}
