package com.linkz.seatreservation.adapter.persistence.entity;

import com.linkz.seatreservation.business.domain.enums.SeatStatus;
import com.linkz.seatreservation.business.domain.model.Seat;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "seats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;
    
    private String label;
    
    @Enumerated(EnumType.STRING)
    private SeatStatus status;
    
    @Version
    private Long version;
    
    public Seat toDomain() {
        return new Seat(id, label, status, version);
    }
    
    public static SeatEntity fromDomain(Seat seat) {
        if (seat == null) return null;
        return SeatEntity.builder()
            .id(seat.id())
            .label(seat.label())
            .status(seat.status())
            .version(seat.version())
            .build();
    }
}
