package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.risk.facade.TamperDetectionPublisher;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Creates the canonical risk.tamper_detected outbox event from trusted server code. */
@Component
@RequiredArgsConstructor
public class OutboxTamperDetectionPublisher implements TamperDetectionPublisher {
    static final String EVENT_TYPE = "RISK_TAMPER_DETECTED";
    private static final Set<String> PATHS = Set.of(
            "free_trial_state", "wallet_pairing", "risk_disclosure_ack", "two_factor_state",
            "product_phase_override", "device_slot_cap", "dev_seed_state", "otp_verification",
            "bill_client_push", "client_minted_id", "charge_fail_rate");

    private final EventOutboxService outboxService;

    @Override
    public String publish(Long userId, String tamperPath, String attackEffect, String blockedAtEndpoint) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("TAMPER_USER_ID_REQUIRED");
        }
        String path = normalize(tamperPath, 64, "TAMPER_PATH_REQUIRED").toLowerCase(Locale.ROOT);
        if (!PATHS.contains(path)) {
            throw new IllegalArgumentException("TAMPER_PATH_INVALID");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("userNo", null);
        payload.put("tamperPath", path);
        payload.put("attackEffect", normalize(attackEffect, 300, "TAMPER_ATTACK_EFFECT_REQUIRED"));
        payload.put("blockedAtEndpoint", normalize(blockedAtEndpoint, 255, "TAMPER_ENDPOINT_REQUIRED"));
        payload.put("eventCount", 1);
        payload.put("occurredAt", LocalDateTime.now().toString());
        payload.put("isServerAuthoritative", true);
        return outboxService.publish("USER", String.valueOf(userId), EVENT_TYPE, payload);
    }

    private String normalize(String value, int maxLength, String missingCode) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(missingCode);
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(missingCode.replace("_REQUIRED", "_TOO_LONG"));
        }
        return normalized;
    }
}
