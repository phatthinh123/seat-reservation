package com.linkz.seatreservation.web.scheduler;

import com.linkz.seatreservation.business.port.in.ReconcilePaymentUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SeatHoldCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(SeatHoldCleanupJob.class);
    private final ReconcilePaymentUseCase reconcileUseCase;

    public SeatHoldCleanupJob(ReconcilePaymentUseCase reconcileUseCase) {
        this.reconcileUseCase = reconcileUseCase;
    }

    @Scheduled(fixedDelay = 60000) // every 1 minute
    public void releaseExpiredHolds() {
        log.info("Scheduled execution: seat hold cleanup");
        try {
            reconcileUseCase.releaseExpiredHolds();
        } catch (Exception e) {
            log.error("Error running seat hold cleanup job: {}", e.getMessage(), e);
        }
    }
}
