package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.AppNotificationPage;
import ffdd.opsconsole.content.domain.NotificationActionReceipt;
import ffdd.opsconsole.content.domain.NotificationActionResult;
import ffdd.opsconsole.content.domain.NotificationCampaignRepository;
import ffdd.opsconsole.content.domain.NotificationEventFact;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AppNotificationService {
    private static final Set<String> PRIORITIES = Set.of("critical", "high", "normal", "low");
    private static final Set<String> ACTIONS = Set.of("cta", "swipe_conversion");
    private final NotificationCampaignRepository repository;
    private final EventOutboxService eventOutboxService;

    @Transactional
    public ApiResult<AppNotificationPage> page(Long userId, String cursor, String priority, Integer limit) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        }
        Long cursorId = parseCursor(cursor);
        if (StringUtils.hasText(cursor) && cursorId == null) {
            return ApiResult.fail(400, "NOTIFICATION_CURSOR_INVALID");
        }
        String normalizedPriority = normalizePriority(priority);
        if (StringUtils.hasText(priority) && normalizedPriority == null) {
            return ApiResult.fail(400, "NOTIFICATION_PRIORITY_INVALID");
        }
        int normalizedLimit = Math.max(1, Math.min(limit == null ? 30 : limit, 100));
        repository.applyRetentionForUser(userId);
        return ApiResult.ok(repository.pageUserNotifications(
                userId, cursorId, normalizedPriority, normalizedLimit));
    }

    @Transactional
    public ApiResult<Void> markRead(Long userId, Long notificationId) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        }
        if (notificationId == null || notificationId <= 0) {
            return ApiResult.fail(400, "NOTIFICATION_ID_INVALID");
        }
        NotificationEventFact fact = repository.lockNotificationEventFact(userId, notificationId).orElse(null);
        if (fact == null) {
            return ApiResult.fail(404, "NOTIFICATION_NOT_FOUND");
        }
        if (fact.alreadyRead()) {
            return ApiResult.ok(null);
        }
        if (repository.markNotificationRead(userId, notificationId)) {
            publishUserEvent(fact, "notification.read", Map.of(
                    "notification_id", fact.notificationId(),
                    "kind", fact.kind(),
                    "priority", fact.priority()));
        }
        return ApiResult.ok(null);
    }

    @Transactional
    public ApiResult<Integer> markAllRead(Long userId) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        }
        var facts = repository.lockUnreadNotificationEventFacts(userId);
        int updated = repository.markAllNotificationsRead(userId);
        if (updated != facts.size()) {
            throw new IllegalStateException("NOTIFICATION_READ_FACT_MISMATCH");
        }
        facts.forEach(fact -> publishUserEvent(fact, "notification.read", Map.of(
                "notification_id", fact.notificationId(),
                "kind", fact.kind(),
                "priority", fact.priority())));
        return ApiResult.ok(updated);
    }

    @Transactional
    public ApiResult<NotificationActionResult> recordAction(
            Long userId, Long notificationId, String action, String idempotencyKey) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        }
        if (notificationId == null || notificationId <= 0) {
            return ApiResult.fail(400, "NOTIFICATION_ID_INVALID");
        }
        if (!StringUtils.hasText(idempotencyKey)
                || idempotencyKey.trim().length() < 8 || idempotencyKey.trim().length() > 128) {
            return ApiResult.fail(400, "IDEMPOTENCY_KEY_REQUIRED");
        }
        String normalizedAction = StringUtils.hasText(action)
                ? action.trim().toLowerCase(Locale.ROOT) : "";
        if (!ACTIONS.contains(normalizedAction)) {
            return ApiResult.fail(422, "NOTIFICATION_ACTION_INVALID");
        }
        NotificationEventFact fact = repository.lockNotificationEventFact(userId, notificationId).orElse(null);
        if (fact == null) {
            return ApiResult.fail(404, "NOTIFICATION_NOT_FOUND");
        }
        String route = canonicalRoute(fact);
        if (!StringUtils.hasText(route)
                || ("swipe_conversion".equals(normalizedAction) && "system".equalsIgnoreCase(fact.kind()))) {
            return ApiResult.fail(422, "NOTIFICATION_ACTION_NOT_ALLOWED");
        }
        String key = idempotencyKey.trim();
        NotificationActionReceipt replay = repository.findNotificationActionReceipt(key).orElse(null);
        if (replay != null) {
            boolean same = userId.equals(replay.userId()) && notificationId.equals(replay.notificationId())
                    && normalizedAction.equals(replay.action()) && route.equals(replay.route());
            return same
                    ? ApiResult.ok(new NotificationActionResult(notificationId, normalizedAction, route, false))
                    : ApiResult.fail(409, "IDEMPOTENCY_KEY_CONFLICT");
        }
        boolean recorded = repository.recordNotificationAction(
                userId, notificationId, normalizedAction, route, key);
        if (recorded) {
            publishUserEvent(fact, "notification.swipe_action_taken", Map.of(
                    "notification_id", fact.notificationId(),
                    "kind", fact.kind(),
                    "action", normalizedAction,
                    "route", route));
        }
        return ApiResult.ok(new NotificationActionResult(notificationId, normalizedAction, route, recorded));
    }

    @Transactional
    public ApiResult<Integer> clearRead(Long userId) {
        return userId == null || userId <= 0
                ? ApiResult.fail(403, "USER_AUTH_REQUIRED")
                : ApiResult.ok(repository.clearReadNotifications(userId));
    }

    private Long parseCursor(String cursor) {
        if (!StringUtils.hasText(cursor)) return null;
        try {
            long value = Long.parseLong(cursor.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String normalizePriority(String priority) {
        if (!StringUtils.hasText(priority)) return null;
        String value = priority.trim().toLowerCase(Locale.ROOT);
        return PRIORITIES.contains(value) ? value : null;
    }

    private void publishUserEvent(NotificationEventFact fact, String eventName, Map<String, Object> payload) {
        eventOutboxService.publishUserEvent(
                "NOTIFICATION", String.valueOf(fact.notificationId()), eventName,
                fact.userId(), fact.phase(), fact.accountAgeMonths(), fact.cohort(), payload);
    }

    private String canonicalRoute(NotificationEventFact fact) {
        if (StringUtils.hasText(fact.ctaHref())) {
            return fact.ctaHref().trim();
        }
        return switch (fact.kind() == null ? "" : fact.kind().trim().toLowerCase(Locale.ROOT)) {
            case "commission" -> "/pages/me/wallet-repurchase";
            case "team" -> "/pages/team/team";
            case "staking" -> "/pages/staking/staking";
            case "market" -> "/pages/market/market";
            case "genesis" -> "/pages/genesis/marketplace";
            default -> "";
        };
    }
}
