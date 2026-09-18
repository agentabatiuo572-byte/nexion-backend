package ffdd.opsconsole.user.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.mapper.NotificationTimeEvidenceMapper;
import ffdd.opsconsole.user.mapper.NotificationTimeEvidenceMapper.EventRow;
import ffdd.opsconsole.user.mapper.NotificationTimeEvidenceMapper.NotificationRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationTimeEvidenceService {
    private final UserOpsRepository users;
    private final NotificationTimeEvidenceMapper mapper;
    private final AdminOperatorRoleResolver roles;
    private final ObjectMapper json;
    private final Clock clock;

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ, timeout = 10)
    public ApiResult<EvidenceView> preview(String userKey, Long notificationId) {
        // Preserve the C1 notification projection, even for custom roles with both authorities.
        if (!Set.of("SUPER_ADMIN", "RISK", "AUDITOR", "GROWTH").contains(String.valueOf(roles.resolveCode()))) {
            return ApiResult.fail(403, "PERMISSION_DENIED");
        }
        if (userKey == null || userKey.isBlank() || notificationId == null || notificationId <= 0) {
            return ApiResult.fail(400, "NOTIFICATION_EVIDENCE_ID_INVALID");
        }
        Long userId = users.findUserIdByLookupKey(userKey.trim()).orElse(null);
        NotificationRow notification = userId == null ? null : mapper.notification(userId, notificationId);
        if (notification == null) return ApiResult.fail(404, "NOTIFICATION_NOT_FOUND");
        // App acknowledgement changes the notification status to READ; delivery receipts remain DELIVERED.
        if (!"NOVA_WELCOME".equals(notification.type())
                || !("DELIVERED".equals(notification.pushStatus()) || "READ".equals(notification.pushStatus()))) {
            return ApiResult.ok(view(notification, "UNSUPPORTED", "仅支持已投递的欢迎通知", null, List.of()));
        }
        String bizNo = notification.bizNo();
        if (bizNo == null || !bizNo.matches("NOVA-welcome-[a-f0-9]{32}")) {
            return ApiResult.ok(view(notification, "CONFLICT", "来源关联无法核对", null, List.of()));
        }
        String sourceId = bizNo.substring("NOVA-welcome-".length());
        var deliveries = mapper.deliveries(String.valueOf(notificationId));
        var sources = mapper.registration(sourceId);
        var receipts = mapper.receipts(sourceId);
        if (deliveries.isEmpty() || sources.isEmpty() || receipts.isEmpty()) {
            return ApiResult.ok(view(notification, "NO_EVIDENCE", "持久证据缺失，保持待核验", null, List.of()));
        }
        if (deliveries.size() != 2 || sources.size() != 1 || receipts.size() != 1) {
            return ApiResult.ok(view(notification, "CONFLICT", "证据数量不唯一，保持待核验", null, List.of()));
        }
        try {
            EventRow push = unique(deliveries, "nova.push_sent"), delivered = unique(deliveries, "notification.delivered");
            EventRow source = sources.get(0);
            check(!push.eventId().equals(delivered.eventId()) && !push.eventId().equals(source.eventId()) && !delivered.eventId().equals(source.eventId()));
            JsonNode p = payload(push), d = payload(delivered), s = payload(source);
            check("NOVA_NOTIFICATION".equals(push.aggregateType()) && "NOTIFICATION".equals(delivered.aggregateType()));
            check(String.valueOf(notificationId).equals(push.aggregateId()) && String.valueOf(notificationId).equals(delivered.aggregateId()));
            check(id(p, "notification_id") == notificationId && id(d, "notification_id") == notificationId);
            check(id(p, "user_id") == userId && id(d, "user_id") == userId && id(s, "user_id") == userId);
            check("welcome".equals(p.path("channel").asText()) && "nova_welcome".equals(d.path("kind").asText()));
            check(bizNo.equals(d.path("campaign_id").asText()));
            check(sourceId.equals(source.eventId()) && "auth.register_completed".equals(source.eventName()));
            check("USER_REGISTRATION".equals(source.aggregateType()) && String.valueOf(userId).equals(source.aggregateId()));
            var receipt = receipts.get(0);
            check(sourceId.equals(receipt.sourceEventId()) && "auth.register_completed".equals(receipt.eventName())
                    && "DELIVERED".equals(receipt.status()) && Integer.valueOf(1).equals(receipt.notificationCount()));
            long pushTs = timestamp(push, p), deliveredTs = timestamp(delivered, d), sourceTs = timestamp(source, s);
            check(deliveredTs >= pushTs && deliveredTs - pushTs <= 5_000 && sourceTs <= pushTs);
            var facts = List.of(new FactView(source.eventId(), source.eventName(), sourceTs),
                    new FactView(push.eventId(), push.eventName(), pushTs),
                    new FactView(delivered.eventId(), delivered.eventName(), deliveredTs));
            return ApiResult.ok(view(notification, "MATCHED", "关联证据一致，仅供核验；尚未校正通知时间",
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(pushTs), DateTimeFormatConfig.BUSINESS_ZONE), facts));
        } catch (EvidenceConflict conflict) {
            return ApiResult.ok(view(notification, "CONFLICT", "身份或时间证据不一致，保持待核验", null, List.of()));
        }
    }

    private JsonNode payload(EventRow row) {
        check(row.eventId() != null && row.eventId().matches("[a-f0-9]{32}"));
        check(Boolean.TRUE.equals(row.authoritative()));
        try {
            JsonNode value = json.readTree(row.payload());
            check(value != null && value.isObject());
            check(row.eventId().equals(value.path("event_id").asText()) && row.eventName().equals(value.path("event_name").asText()));
            check(value.path("is_server_authoritative").isBoolean() && value.path("is_server_authoritative").booleanValue());
            return value;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException error) {
            throw new EvidenceConflict();
        }
    }

    private long timestamp(EventRow row, JsonNode payload) {
        long ts = id(payload, "ts");
        check(ts >= 946684800000L && ts <= clock.millis());
        check(row.eventTs() != null);
        long dbTs = row.eventTs().atZone(DateTimeFormatConfig.BUSINESS_ZONE).toInstant().toEpochMilli();
        check(Math.abs(dbTs - ts) <= 2_000);
        return ts;
    }

    private static long id(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        check(value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0);
        return value.longValue();
    }

    private static EventRow unique(List<EventRow> rows, String name) {
        List<EventRow> matches = rows.stream().filter(row -> name.equals(row.eventName())).toList();
        check(matches.size() == 1);
        return matches.get(0);
    }

    private static void check(boolean valid) { if (!valid) throw new EvidenceConflict(); }
    private static final class EvidenceConflict extends RuntimeException {}
    private static EvidenceView view(NotificationRow row, String status, String reason, LocalDateTime deliveryTime, List<FactView> facts) {
        return new EvidenceView(row.notificationId(), status, reason, row.createdAt(), deliveryTime, "Asia/Shanghai", facts);
    }
    public record FactView(String eventId, String eventName, long timestampMillis) {}
    public record EvidenceView(Long notificationId, String status, String reason, LocalDateTime storedCreatedAt,
                               LocalDateTime deliveryFactTime, String timeZone, List<FactView> facts) {}
}

