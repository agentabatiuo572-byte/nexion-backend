package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.AppNotificationPage;
import ffdd.opsconsole.content.domain.NotificationCampaignRepository;
import ffdd.opsconsole.shared.api.ApiResult;
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
    private final NotificationCampaignRepository repository;

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
        return repository.markNotificationRead(userId, notificationId)
                ? ApiResult.ok(null)
                : ApiResult.fail(404, "NOTIFICATION_NOT_FOUND");
    }

    @Transactional
    public ApiResult<Integer> markAllRead(Long userId) {
        return userId == null || userId <= 0
                ? ApiResult.fail(403, "USER_AUTH_REQUIRED")
                : ApiResult.ok(repository.markAllNotificationsRead(userId));
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
}
