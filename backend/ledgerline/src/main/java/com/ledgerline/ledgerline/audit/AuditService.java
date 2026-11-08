package com.ledgerline.ledgerline.audit;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import com.ledgerline.ledgerline.domain.AuditLogEntity;
import com.ledgerline.ledgerline.domain.UserEntity;
import com.ledgerline.ledgerline.repository.AuditLogRepository;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

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

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
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
        String detailsJson = sanitizeAndSerialize(metadata);
        AuditLogEntity entity = new AuditLogEntity(actor, action, resourceType, resourceId, detailsJson);
        return auditLogRepository.save(entity);
    }

    private String sanitizeAndSerialize(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        Map<String, Object> sanitized = new HashMap<>();
        for (Map.Entry<String, ?> entry : metadata.entrySet()) {
            String key = entry.getKey();
            if (key != null && !SENSITIVE_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                sanitized.put(key, entry.getValue());
            }
        }
        try {
            return objectMapper.writeValueAsString(sanitized);
        } catch (Exception e) {
            log.error("Failed to serialize audit log details", e);
            return "{}";
        }
    }
}
