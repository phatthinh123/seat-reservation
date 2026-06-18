package com.tpthinh.seatreservation.web.scheduler;

import com.tpthinh.seatreservation.business.port.in.ReconcilePaymentUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationJob {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);
    private final ReconcilePaymentUseCase reconcileUseCase;

    public ReconciliationJob(ReconcilePaymentUseCase reconcileUseCase) {
        this.reconcileUseCase = reconcileUseCase;
    }

    @Scheduled(fixedDelay = 300000) // every 5 minutes
    public void reconcilePayments() {
        log.info("Scheduled execution: payment reconciliation");
        try {
            reconcileUseCase.reconcilePendingBookings();
        } catch (Exception e) {
            log.error("Error running payment reconciliation job: {}", e.getMessage(), e);
        }
    }
}
