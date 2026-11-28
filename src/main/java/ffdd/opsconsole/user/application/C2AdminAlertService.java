package ffdd.opsconsole.user.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.config.StorageTime;
import ffdd.opsconsole.shared.outbox.EventConsumerDelivery;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.mapper.EventConsumerDeliveryMapper.OriginalAlertEvent;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read model for the human-visible C2 high-risk alert feed. */
@Service
@RequiredArgsConstructor
public class C2AdminAlertService {
    private static final int ALERT_LIMIT = 30;
    private static final Pattern USER_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Set<String> USER_AGGREGATES = Set.of("USER", "USER_ACCOUNT_LIST", "USER_SECURITY");
    private final EventConsumerDeliveryService deliveryService;

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> alerts() {
        List<EventConsumerDelivery> deliveries = deliveryService
                .listByStatus(C2HighRiskAdminAlertConsumer.CONSUMER_GROUP, "SUCCESS", ALERT_LIMIT);
        Map<String, OriginalAlertEvent> events = deliveryService.listOriginalAlertEvents(deliveries.stream()
                        .map(EventConsumerDelivery::getEventId).toList()).stream()
                .collect(Collectors.toMap(OriginalAlertEvent::eventId, Function.identity()));
        List<Map<String, Object>> alerts = deliveries.stream()
                .map(delivery -> toAlert(delivery, events.get(delivery.getEventId())))
                .toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("alerts", alerts);
        response.put("consumerGroup", C2HighRiskAdminAlertConsumer.CONSUMER_GROUP);
        response.put("source", "nx_event_consumer_delivery");
        return ApiResult.ok(response);
    }

    private Map<String, Object> toAlert(EventConsumerDelivery delivery, OriginalAlertEvent event) {
        String eventType = delivery.getEventType() == null ? "" : delivery.getEventType();
        String target = delivery.getAggregateId() == null ? "未知对象" : delivery.getAggregateId();
        String targetLabel = "USER_IMPERSONATION".equals(delivery.getAggregateType())
                ? "模拟会话 " + target
                : "用户ID " + target;
        Map<String, Object> alert = new LinkedHashMap<>();
        boolean c5 = eventType.equals("admin.2fa_disabled")
                || eventType.equals("admin.password_reset_requested")
                || eventType.equals("admin.user_unlocked")
                || eventType.equals("admin.session_revoked")
                || eventType.equals("auth.login_locked")
                || eventType.equals("auth.refresh_token_reuse_detected");
        alert.put("id", (c5 ? "C5-" : "C2-") + delivery.getEventId());
        alert.put("domain", c5 ? "C5" : "C2");
        alert.put("level", level(eventType));
        alert.put("title", title(eventType));
        alert.put("hint", targetLabel + " · " + time(delivery));
        alert.put("eventType", eventType);
        alert.put("target", target);
        alert.put("targetUserId", targetUserId(delivery, event));
        alert.put("occurredAt", occurredAt(event));
        return alert;
    }

    private String targetUserId(EventConsumerDelivery delivery, OriginalAlertEvent event) {
        if (event != null) {
            if (isUserId(event.targetUserId())) {
                return event.targetUserId();
            }
            if (isUserId(event.userId())) {
                return event.userId();
            }
        }
        String aggregateType = delivery.getAggregateType();
        String aggregateId = delivery.getAggregateId();
        return aggregateType != null && USER_AGGREGATES.contains(aggregateType) && isUserId(aggregateId)
                ? aggregateId : null;
    }

    private boolean isUserId(String value) {
        return value != null && USER_ID.matcher(value).matches();
    }

    private String occurredAt(OriginalAlertEvent event) {
        if (event == null) {
            return null;
        }
        if (event.occurredAt() != null) {
            try {
                var parsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(event.occurredAt(),
                        OffsetDateTime::from, LocalDateTime::from);
                OffsetDateTime time = parsed instanceof OffsetDateTime offsetTime ? offsetTime
                        : ((LocalDateTime) parsed).atZone(StorageTime.ZONE).toOffsetDateTime();
                return time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } catch (DateTimeParseException ignored) {
                // A malformed historical field falls back to the original event row's timestamp.
            }
        }
        return event.originalTimestamp().atZone(StorageTime.ZONE)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private String title(String eventType) {
        return switch (eventType) {
            case "admin.user_frozen" -> "C2 账户已冻结";
            case "admin.user_unfrozen" -> "C2 账户已恢复";
            case "admin.user_impersonation_started" -> "C2 只读模拟登录已发起";
            case "admin.user_impersonation_ended" -> "C2 只读模拟登录已结束";
            case "admin.2fa_disabled" -> "C5 两步验证已人工关闭";
            case "admin.password_reset_requested" -> "C5 已要求用户重设密码";
            case "admin.user_unlocked" -> "C5 用户登录锁已解除";
            case "admin.session_revoked" -> "C5 用户会话已吊销";
            case "auth.login_locked" -> "C5 用户登录已触发安全锁定";
            case "auth.refresh_token_reuse_detected" -> "C5 检测到刷新凭证复用并已整链吊销";
            default -> "C2 高风险账户动作";
        };
    }

    private String level(String eventType) {
        return switch (eventType) {
            case "admin.user_unfrozen", "admin.user_impersonation_ended" -> "mid";
            default -> "high";
        };
    }

    private String time(EventConsumerDelivery delivery) {
        LocalDateTime value = delivery.getProcessedAt() != null
                ? delivery.getProcessedAt()
                : delivery.getUpdatedAt();
        return value == null ? "刚刚" : value.toString().replace('T', ' ');
    }
}
