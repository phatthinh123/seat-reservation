package com.tpthinh.seatreservation.adapter.persistence.entity;

import com.tpthinh.seatreservation.business.domain.enums.SeatStatus;
import com.tpthinh.seatreservation.business.domain.model.Seat;
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
}
