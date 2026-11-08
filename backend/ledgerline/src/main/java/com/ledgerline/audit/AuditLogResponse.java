package com.ledgerline.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.ledgerline.domain.AuditLogEntity;

public record AuditLogResponse(
        UUID id,
        UUID actorUserId,
        String action,
        String resourceType,
        UUID resourceId,
        Map<String, Object> details,
        Instant createdAt
) {
    public static AuditLogResponse fromEntity(AuditLogEntity entity) {
        UUID actorUserId = entity.getActor() != null ? entity.getActor().getId() : null;
        return new AuditLogResponse(
                entity.getId(),
                actorUserId,
                entity.getAction(),
                entity.getResourceType(),
                entity.getResourceId(),
                entity.getDetails(),
                entity.getCreatedAt()
        );
    }
}
