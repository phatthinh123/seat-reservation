package com.linkz.seatreservation.adapter.persistence.repo;

import com.linkz.seatreservation.adapter.persistence.entity.AuditLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface AuditLogJpaRepository extends JpaRepository<AuditLogEntity, UUID> {
    @Query("SELECT a FROM AuditLogEntity a " +
           "WHERE (:entityType IS NULL OR :entityType = '' OR a.entityType = :entityType) " +
           "AND (:action IS NULL OR :action = '' OR a.action = :action) " +
           "ORDER BY a.createdAt DESC")
    List<AuditLogEntity> queryLogs(@Param("entityType") String entityType, 
                                   @Param("action") String action, 
                                   Pageable pageable);
}
