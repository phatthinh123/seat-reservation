package com.linkz.seatreservation.adapter.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "webhook_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;
    
    private String provider;
    
    @Column(name = "event_id", unique = true)
    private String eventId;
    
    @Column(name = "raw_payload")
    private String rawPayload;
    
    private String status;
    
    @Column(name = "processed_at")
    private LocalDateTime processedAt;
    
    private String error;
    
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
