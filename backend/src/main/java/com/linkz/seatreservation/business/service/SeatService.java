package com.linkz.seatreservation.business.service;

import com.linkz.seatreservation.business.domain.model.Seat;
import com.linkz.seatreservation.business.port.in.GetSeatsUseCase;
import com.linkz.seatreservation.business.port.external.CachePort;
import com.linkz.seatreservation.business.port.external.SeatRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class SeatService implements GetSeatsUseCase {
    private static final Logger log = LoggerFactory.getLogger(SeatService.class);

    private final SeatRepositoryPort seatRepo;
    private final CachePort cachePort;

    public SeatService(SeatRepositoryPort seatRepo, CachePort cachePort) {
        this.seatRepo = seatRepo;
        this.cachePort = cachePort;
    }

    @Override
    public List<Seat> getSeats() {
        List<?> rawIds = cachePort.get("seats:ids", List.class);
        List<String> seatIds = new ArrayList<>();
        if (rawIds != null) {
            for (Object obj : rawIds) {
                seatIds.add(String.valueOf(obj));
            }
        }
        
        if (seatIds.isEmpty()) {
            log.info("Seat IDs cache miss, loading all from DB");
            List<Seat> dbSeats = seatRepo.findAll();
            seatIds = dbSeats.stream().map(s -> s.id().toString()).toList();
            cachePort.put("seats:ids", seatIds, 24 * 3600);
            
            for (Seat seat : dbSeats) {
                cachePort.put("seat:cache:" + seat.id(), seat, 24 * 3600);
            }
            return dbSeats;
        }

        List<String> cacheKeys = seatIds.stream().map(id -> "seat:cache:" + id).toList();
        List<Seat> cachedSeats = cachePort.multiGet(cacheKeys, Seat.class);
        
        List<Seat> results = new ArrayList<>();
        for (int i = 0; i < seatIds.size(); i++) {
            UUID id = UUID.fromString(seatIds.get(i));
            Seat seat = (cachedSeats != null && i < cachedSeats.size()) ? cachedSeats.get(i) : null;
            
            if (seat == null) {
                log.info("Seat {} cache miss, lazy loading from DB", id);
                seat = seatRepo.findById(id).orElse(null);
                if (seat != null) {
                    cachePort.put("seat:cache:" + id, seat, 24 * 3600);
                }
            }
            
            if (seat != null) {
                results.add(seat);
            }
        }
        
        return results;
    }
}
