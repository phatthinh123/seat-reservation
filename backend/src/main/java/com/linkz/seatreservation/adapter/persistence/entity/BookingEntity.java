package com.tpthinh.seatreservation.adapter.persistence.entity;

import com.tpthinh.seatreservation.business.domain.enums.BookingStatus;
import com.tpthinh.seatreservation.business.domain.model.Booking;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;
    
    @Column(name = "user_id")
    private String userId;
    
    @Column(name = "seat_id")
    private UUID seatId;
    
    @Enumerated(EnumType.STRING)
    private BookingStatus status;
    
    @Column(name = "idempotency_key")
    private String idempotencyKey;
    
    @Column(name = "hold_expires_at")
    private LocalDateTime holdExpiresAt;
    
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
