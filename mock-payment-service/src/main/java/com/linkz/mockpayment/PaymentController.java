package com.linkz.mockpayment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
public class PaymentController {
    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);
    
    // In-memory store of payments
    public static final Map<String, PaymentRecord> payments = new ConcurrentHashMap<>();

    private final WebhookDeliveryService webhookDelivery;

    public PaymentController(WebhookDeliveryService webhookDelivery) {
        this.webhookDelivery = webhookDelivery;
    }

    public static void updatePaymentStatus(String paymentId, PaymentStatus status) {
        payments.computeIfPresent(paymentId, (k, v) -> v.withStatus(status));
    }

    @PostMapping("/pay")
    public PaymentResponse pay(@RequestBody PaymentRequest req) {
        String paymentId = UUID.randomUUID().toString();
        
        // Check if global simulation flags override the simulateFail
        boolean actualFail = req.simulateFail() || SimulationController.nextFail.getAndSet(false);
        
        payments.put(paymentId, new PaymentRecord(req.bookingId(), PaymentStatus.PENDING, actualFail));
        
        // Deliver webhook (async)
        webhookDelivery.scheduleDelivery(paymentId, req.bookingId(), req.callbackUrl(), actualFail);
        
        log.info("Initiated mock payment {} for booking {}, status=PENDING, actualFail={}", paymentId, req.bookingId(), actualFail);
        return new PaymentResponse(paymentId, "PENDING");
    }

    @GetMapping("/payment/{id}")
    public PaymentStatusResponse getStatus(@PathVariable String id) {
        PaymentRecord record = payments.get(id);
        if (record == null) {
            log.warn("Payment {} not found in mock store", id);
            return new PaymentStatusResponse(id, "NOT_FOUND");
        }
        return new PaymentStatusResponse(id, record.status().name());
    }

    @PostMapping("/refund/{id}")
    public void refund(@PathVariable String id) {
        log.info("Refunding mock payment {}", id);
        payments.computeIfPresent(id, (k, v) -> v.withStatus(PaymentStatus.REFUNDED));
    }

    public record PaymentRequest(String bookingId, String callbackUrl, boolean simulateFail) {}
    public record PaymentResponse(String paymentId, String status) {}
    public record PaymentStatusResponse(String paymentId, String status) {}
}
