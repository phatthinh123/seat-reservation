package com.linkz.seatreservation.adapter.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkz.seatreservation.adapter.persistence.entity.AuditLogEntity;
import com.linkz.seatreservation.adapter.persistence.mapper.EntityMapper;
import com.linkz.seatreservation.adapter.persistence.repo.AuditLogJpaRepository;
import com.linkz.seatreservation.business.domain.model.AuditEntry;
import com.linkz.seatreservation.business.port.external.AuditPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class AuditJpaAdapter implements AuditPort {
    private final AuditLogJpaRepository repository;
    private final EntityMapper mapper;
    private final ObjectMapper objectMapper;

    public AuditJpaAdapter(AuditLogJpaRepository repository, EntityMapper mapper, ObjectMapper objectMapper) {
        this.repository = repository;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void log(String actor, String action, String entityType, String entityId, Object beforeState, Object afterState) {
        String beforeJson = beforeState != null ? toJson(beforeState) : null;
        String afterJson = afterState != null ? toJson(afterState) : null;
        
        AuditLogEntity entity = AuditLogEntity.builder()
            .actor(actor)
            .action(action)
            .entityType(entityType)
            .entityId(entityId)
            .beforeState(beforeJson)
            .afterState(afterJson)
            .build();
        repository.save(entity);
    }

    @Override
    public List<AuditEntry> queryLogs(String entityType, String action, int limit) {
        return repository.queryLogs(entityType, action, PageRequest.of(0, limit))
            .stream()
            .map(mapper::toDomain)
            .toList();
    }

    private String toJson(Object obj) {
        if (obj instanceof String) {
            String str = (String) obj;
            if ((str.startsWith("{") && str.endsWith("}")) || (str.startsWith("[") && str.endsWith("]"))) {
                return str;
            }
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            try {
                return objectMapper.writeValueAsString(String.valueOf(obj));
            } catch (Exception ex) {
                return null;
            }
        }
    }
}
