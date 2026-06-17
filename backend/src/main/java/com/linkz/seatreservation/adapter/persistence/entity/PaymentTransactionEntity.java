package com.linkz.seatreservation.adapter.persistence.entity;

import com.linkz.seatreservation.business.domain.enums.PaymentStatus;
import com.linkz.seatreservation.business.domain.model.Payment;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payment_transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentTransactionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;
    
    @Column(name = "booking_id")
    private UUID bookingId;
    
    @Column(name = "external_payment_id")
    private String externalPaymentId;
    
    private BigDecimal amount;
    
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;
    
    @Column(name = "raw_payload")
    private String rawPayload;
    
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
    
    public Payment toDomain() {
        return new Payment(id, bookingId, externalPaymentId, amount, status, rawPayload, createdAt, updatedAt);
    }
    
    public static PaymentTransactionEntity fromDomain(Payment payment) {
        if (payment == null) return null;
        return PaymentTransactionEntity.builder()
            .id(payment.id())
            .bookingId(payment.bookingId())
            .externalPaymentId(payment.externalPaymentId())
            .amount(payment.amount())
            .status(payment.status())
            .rawPayload(payment.rawPayload())
            .createdAt(payment.createdAt())
            .updatedAt(payment.updatedAt())
            .build();
    }
}
