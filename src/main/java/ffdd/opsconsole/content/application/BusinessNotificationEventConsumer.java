package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.mapper.BusinessNotificationMapper;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Independent durable consumer: retries cannot re-execute the originating business transaction. */
@Component
@RequiredArgsConstructor
public class BusinessNotificationEventConsumer {
    static final String GROUP = "app-business-notification";
    private static final Set<String> WITHDRAWAL = Set.of("withdraw.submitted", "withdraw.approved",
            "withdraw.rejected", "withdraw.delayed", "withdraw.frozen", "withdraw.unfrozen",
            "withdraw.refunded", "withdraw.confirmed", "withdraw.processing", "withdraw.payout_held", "withdraw.account_frozen", "withdraw.account_restored");
    private static final Set<String> REWARDS = Set.of("quest.claimed", "event.claimed", "daily.milestone_claimed");
    private final EventConsumerDeliveryService delivery;
    private final BusinessNotificationMapper mapper;
    private final ObjectMapper json;
    private final PlatformTransactionManager transactionManager;

    @EventListener
    public void onOutboxMessage(EventOutboxMessage message) {
        if (message == null || !supported(message.getEventType())) return;
        if (message.getEventId() == null || message.getEventId().isBlank())
            throw new IllegalArgumentException("BUSINESS_NOTIFICATION_EVENT_ID_REQUIRED");
        var claim = delivery.claim(message, GROUP, "spring-local-business-notification", message.getEventId(), 0);
        if (!claim.claimed()) {
            if (!Set.of("SUCCESS", "SKIPPED").contains(claim.status()))
                throw new IllegalStateException("BUSINESS_NOTIFICATION_DELIVERY_INCOMPLETE");
            return;
        }
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> project(message));
        } catch (RuntimeException ex) {
            delivery.markFailure(GROUP, claim.eventId(), 0, "BUSINESS_NOTIFICATION_PROJECTION_FAILED");
            throw ex;
        }
    }

    private void project(EventOutboxMessage message) {
        JsonNode payload;
        try { payload = json.readTree(message.getPayload()); }
        catch (Exception ex) { throw new IllegalArgumentException("BUSINESS_NOTIFICATION_PAYLOAD_INVALID", ex); }
        String event = message.getEventType();
        String aggregate = message.getAggregateType();
        String id = message.getAggregateId();
        Long userId = null;
        if (Boolean.TRUE.equals(message.getServerAuthoritative()) && payload != null) {
            if (WITHDRAWAL.contains(event) && "WITHDRAWAL".equals(aggregate)) userId = mapper.withdrawalOwner(id);
            else if (("checkout.completed".equals(event) && "ORDER".equals(aggregate))
                    || ("order.refunded".equals(event) && "E4_ORDER".equals(aggregate))) userId = mapper.orderOwner(id);
            else if (event.startsWith("device.") && "USER_DEVICE".equals(aggregate)) userId = mapper.deviceOwner(id);
            else if (("quest.claimed".equals(event) && "MISSION".equals(aggregate))
                    || ("event.claimed".equals(event) && "EVENT_QUEST".equals(aggregate))
                    || ("daily.milestone_claimed".equals(event) && "DAILY_MILESTONE".equals(aggregate))
                    || ("auth.password_reset_completed".equals(event) && "USER_SECURITY".equals(aggregate)))
                userId = positiveId(payload.path("user_id"));
        }
        Long assertedOwner = payload == null ? null : positiveId(payload.path("user_id"));
        if (userId == null || userId <= 0 || (assertedOwner != null && !userId.equals(assertedOwner))
                || ("USER_SECURITY".equals(aggregate) && !String.valueOf(userId).equals(id))) {
            delivery.markSkipped(GROUP, message.getEventId(), "AUTHORITATIVE_RECIPIENT_UNAVAILABLE");
            return;
        }
        String language = mapper.language(userId);
        if (language == null) {
            delivery.markSkipped(GROUP, message.getEventId(), "RECIPIENT_UNAVAILABLE");
            return;
        }
        int locale = language.toLowerCase(Locale.ROOT).startsWith("zh") ? 0
                : language.toLowerCase(Locale.ROOT).startsWith("vi") ? 2 : 1;
        String kind = WITHDRAWAL.contains(event) ? "WITHDRAWAL" : REWARDS.contains(event) ? "REWARD"
                : event.startsWith("device.") ? "DEVICE" : event.startsWith("auth.") ? "SECURITY" : "ORDER";
        String title = title(event)[locale];
        String body = new String[]{"请查看详情了解最新状态。", "View details for the latest status.", "Xem chi tiết để biết trạng thái mới nhất."}[locale];
        String label = new String[]{"查看详情", "View details", "Xem chi tiết"}[locale];
        String href = switch (kind) {
            case "WITHDRAWAL" -> "/pages/me/wallet-withdraw-tracking?id=" + encode(id);
            case "ORDER" -> "/pages/store/order-detail?id=" + encode(id);
            case "DEVICE" -> "/pages/me/devices";
            case "REWARD" -> "/pages/me/rewards";
            default -> "/pages/me/security";
        };
        // Withdrawal provider replay can carry a new event ID; one public state still produces one notice.
        String identity = WITHDRAWAL.contains(event) ? event + ":" + id : message.getEventId();
        mapper.deliver("BUSINESS:" + digest(identity), userId, kind,
                Set.of("WITHDRAWAL", "SECURITY").contains(kind) ? "high" : "normal", title, body, label, href);
        delivery.markSuccess(GROUP, message.getEventId(), 1);
    }

    private static Long positiveId(JsonNode node) {
        if (!node.isIntegralNumber() && !node.isTextual()) return null;
        try { long value = Long.parseLong(node.asText()); return value > 0 ? value : null; }
        catch (NumberFormatException ex) { return null; }
    }

    private static boolean supported(String event) {
        return event != null && (WITHDRAWAL.contains(event) || REWARDS.contains(event)
                || Set.of("checkout.completed", "order.refunded", "device.activated", "device.deactivated",
                        "auth.password_reset_completed").contains(event));
    }

    private static String[] title(String event) {
        return switch (event) {
            case "withdraw.submitted" -> new String[]{"提现申请已提交", "Withdrawal submitted", "Đã gửi yêu cầu rút tiền"};
            case "withdraw.approved" -> new String[]{"提现审核已通过", "Withdrawal approved", "Yêu cầu rút tiền đã được duyệt"};
            case "withdraw.rejected" -> new String[]{"提现申请未通过", "Withdrawal rejected", "Yêu cầu rút tiền bị từ chối"};
            case "withdraw.delayed" -> new String[]{"提现审核时间已更新", "Withdrawal review updated", "Đã cập nhật thời gian xét duyệt rút tiền"};
            case "withdraw.frozen", "withdraw.payout_held", "withdraw.account_frozen" -> new String[]{"提现处理中，请等待确认", "Withdrawal awaiting review", "Lệnh rút tiền đang chờ xác minh"};
            case "withdraw.unfrozen", "withdraw.account_restored" -> new String[]{"提现已恢复处理", "Withdrawal processing resumed", "Đã tiếp tục xử lý rút tiền"};
            case "withdraw.processing" -> new String[]{"提现正在转账", "Withdrawal transfer processing", "Đang chuyển tiền rút"};
            case "withdraw.refunded" -> new String[]{"提现金额已退回", "Withdrawal refunded", "Đã hoàn lại tiền rút"};
            case "withdraw.confirmed" -> new String[]{"提现已完成", "Withdrawal completed", "Đã hoàn tất rút tiền"};
            case "checkout.completed" -> new String[]{"订单支付成功", "Order payment completed", "Đã thanh toán đơn hàng"};
            case "order.refunded" -> new String[]{"订单已退款", "Order refunded", "Đơn hàng đã được hoàn tiền"};
            case "device.activated" -> new String[]{"设备已启用", "Device activated", "Thiết bị đã được kích hoạt"};
            case "device.deactivated" -> new String[]{"设备状态已更新", "Device status updated", "Đã cập nhật trạng thái thiết bị"};
            case "auth.password_reset_completed" -> new String[]{"账户密码已重置", "Account password reset", "Đã đặt lại mật khẩu tài khoản"};
            default -> new String[]{"奖励已领取", "Reward claimed", "Đã nhận phần thưởng"};
        };
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
