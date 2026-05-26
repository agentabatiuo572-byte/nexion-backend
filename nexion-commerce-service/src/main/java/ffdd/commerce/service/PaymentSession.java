package ffdd.commerce.service;

import java.time.LocalDateTime;

public record PaymentSession(String providerPaymentId, String checkoutUrl, LocalDateTime expiresAt) {
}
