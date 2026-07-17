package ffdd.opsconsole.emergency.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import ffdd.opsconsole.emergency.domain.TamperEventRecord;
import ffdd.opsconsole.risk.facade.RiskTamperSignalFacade;
import ffdd.opsconsole.risk.facade.RiskTamperSignalFacade.TamperProjectionResult;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Atomically projects one canonical business rejection into K4, B5, J3 and the success receipt. */
@Component
@RequiredArgsConstructor
public class TamperDetectedEventProjector {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final Set<String> PATHS = Set.of(
            "free_trial_state", "wallet_pairing", "risk_disclosure_ack", "two_factor_state",
            "product_phase_override", "device_slot_cap", "dev_seed_state", "otp_verification",
            "bill_client_push", "client_minted_id", "charge_fail_rate");

    private final EmergencyControlRepository repository;
    private final RiskTamperSignalFacade riskFacade;
    private final EventConsumerDeliveryService deliveryService;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public void project(EventOutboxMessage message, String deliveryEventId) {
        Map<String, Object> payload = readPayload(message.getPayload());
        if (!bool(payload.get("isServerAuthoritative"))) {
            throw new IllegalArgumentException("TAMPER_EVENT_NOT_SERVER_CANONICAL");
        }
        String path = text(payload.get("tamperPath")).toLowerCase(Locale.ROOT);
        if (!PATHS.contains(path)) {
            throw new IllegalArgumentException("TAMPER_PATH_INVALID");
        }
        Long userId = longValue(payload.get("userId"));
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("TAMPER_USER_ID_REQUIRED");
        }
        String userNoValue = text(payload.get("userNo"));
        String userNo = StringUtils.hasText(userNoValue) ? userNoValue : null;
        String attackEffect = text(payload.get("attackEffect"));
        String endpoint = text(payload.get("blockedAtEndpoint"));
        int eventCount = Math.max(1, Math.min(1000, intValue(payload.get("eventCount"), 1)));
        boolean feedK4 = repository.settingValue("emergency.tamper.alert.feedK4")
                .map(Boolean::parseBoolean)
                .orElse(true);
        TamperProjectionResult projection = riskFacade.recordTamperSignal(
                message.getEventId(), userId, userNo, path, attackEffect, endpoint, eventCount, feedK4);
        repository.recordTamperEvent(new TamperEventRecord(
                message.getEventId(), userId, userNo, path, pathName(path), attackEffect, endpoint,
                projection.k4Accepted(), projection.k4Delta(), projection.b5Accepted(), eventCount,
                localDateTime(payload.get("occurredAt")).orElseGet(LocalDateTime::now)));
        deliveryService.markSuccess(TamperDetectedEventConsumer.CONSUMER_GROUP, deliveryEventId, 1);
    }

    private Map<String, Object> readPayload(String payload) {
        try {
            return objectMapper.readValue(payload, MAP_TYPE);
        } catch (Exception ex) {
            throw new IllegalArgumentException("TAMPER_EVENT_PAYLOAD_INVALID", ex);
        }
    }

    private Optional<LocalDateTime> localDateTime(Object value) {
        try {
            return StringUtils.hasText(text(value)) ? Optional.of(LocalDateTime.parse(text(value))) : Optional.empty();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("TAMPER_OCCURRED_AT_INVALID", ex);
        }
    }

    private String pathName(String path) {
        return switch (path) {
            case "free_trial_state" -> "试用状态篡改";
            case "wallet_pairing" -> "钱包配对状态篡改";
            case "risk_disclosure_ack" -> "风险披露确认篡改";
            case "two_factor_state" -> "两步验证状态篡改";
            case "product_phase_override" -> "产品阶段覆盖";
            case "device_slot_cap" -> "设备槽位上限篡改";
            case "dev_seed_state" -> "设备开发种子篡改";
            case "otp_verification" -> "验证码校验绕过";
            case "bill_client_push" -> "客户端账单伪造";
            case "client_minted_id" -> "客户端业务 ID 伪造";
            case "charge_fail_rate" -> "扣款成功率篡改";
            default -> path;
        };
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean bool(Object value) {
        return value instanceof Boolean flag ? flag : "true".equalsIgnoreCase(text(value));
    }

    private Long longValue(Object value) {
        try {
            return value == null ? null : Long.valueOf(text(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int intValue(Object value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(text(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
