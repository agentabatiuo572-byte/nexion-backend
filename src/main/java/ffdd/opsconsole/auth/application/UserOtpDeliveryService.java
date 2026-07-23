package ffdd.opsconsole.auth.application;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** Delivers login OTPs through the configured trusted SMS/provider webhook. */
@Service
@RequiredArgsConstructor
public class UserOtpDeliveryService {
    @Value("${nexion.auth.user-otp.delivery-url:}")
    private final String deliveryUrl;
    private final RestClient.Builder restClientBuilder;

    public boolean available() {
        return StringUtils.hasText(deliveryUrl);
    }

    public void deliver(String countryCode, String phone, String challengeNo, String code, int ttlMinutes) {
        if (!available()) throw new IllegalStateException("USER_OTP_DELIVERY_UNAVAILABLE");
        restClientBuilder.build().post()
                .uri(deliveryUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "countryCode", countryCode,
                        "phone", phone,
                        "challengeNo", challengeNo,
                        "code", code,
                        "ttlMinutes", ttlMinutes))
                .retrieve()
                .toBodilessEntity();
    }
}
