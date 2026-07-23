package ffdd.opsconsole.user.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.outbox.EventConsumerDelivery;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Read model for the human-visible C2 high-risk alert feed. */
@Service
@RequiredArgsConstructor
public class C2AdminAlertService {
    private static final int ALERT_LIMIT = 30;
    private final EventConsumerDeliveryService deliveryService;

    public ApiResult<Map<String, Object>> alerts() {
        List<Map<String, Object>> alerts = deliveryService
                .listByStatus(C2HighRiskAdminAlertConsumer.CONSUMER_GROUP, "SUCCESS", ALERT_LIMIT)
                .stream()
                .map(this::toAlert)
                .toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("alerts", alerts);
        response.put("consumerGroup", C2HighRiskAdminAlertConsumer.CONSUMER_GROUP);
        response.put("source", "nx_event_consumer_delivery");
        return ApiResult.ok(response);
    }

    private Map<String, Object> toAlert(EventConsumerDelivery delivery) {
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
        return alert;
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
