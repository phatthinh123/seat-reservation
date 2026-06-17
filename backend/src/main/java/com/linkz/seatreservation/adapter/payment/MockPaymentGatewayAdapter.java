package com.linkz.seatreservation.adapter.payment;

import com.linkz.seatreservation.business.port.external.PaymentGatewayPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Component
public class MockPaymentGatewayAdapter implements PaymentGatewayPort {
    private final RestTemplate restTemplate;
    
    @Value("${mock-payment.url}")
    private String mockPaymentUrl;

    @Value("${mock-payment.callback-url}")
    private String callbackUrl;

    public MockPaymentGatewayAdapter() {
        this.restTemplate = new RestTemplate();
    }

    @Override
    public String initiatePayment(UUID bookingId, BigDecimal amount, String customCallbackUrl, boolean simulateFail) {
        String url = mockPaymentUrl + "/pay";
        String actualCallback = (customCallbackUrl != null && !customCallbackUrl.isBlank()) ? customCallbackUrl : callbackUrl;
        
        Map<String, Object> request = Map.of(
            "bookingId", bookingId.toString(),
            "callbackUrl", actualCallback,
            "simulateFail", simulateFail
        );

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (String) response.getBody().get("paymentId");
            }
            throw new RuntimeException("Failed to initiate payment, response code: " + response.getStatusCode());
        } catch (Exception e) {
            throw new RuntimeException("Error communicating with mock payment service: " + e.getMessage(), e);
        }
    }

    @Override
    public void refund(String externalPaymentId) {
        String url = mockPaymentUrl + "/refund/" + externalPaymentId;
        try {
            restTemplate.postForLocation(url, null);
        } catch (Exception e) {
            throw new RuntimeException("Error refunding payment " + externalPaymentId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String queryStatus(String externalPaymentId) {
        String url = mockPaymentUrl + "/payment/" + externalPaymentId;
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (String) response.getBody().get("status");
            }
            throw new RuntimeException("Failed to query payment status, response code: " + response.getStatusCode());
        } catch (Exception e) {
            throw new RuntimeException("Error querying status for payment " + externalPaymentId + ": " + e.getMessage(), e);
        }
    }
}
