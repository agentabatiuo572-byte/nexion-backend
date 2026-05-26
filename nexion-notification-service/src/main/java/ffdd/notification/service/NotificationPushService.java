package ffdd.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.notification.domain.Notification;
import ffdd.notification.dto.NotificationPushResult;
import ffdd.notification.mapper.NotificationMapper;
import ffdd.notification.push.PushProvider;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class NotificationPushService {
    private static final String PUSH_PENDING = "PENDING";
    private static final String PUSH_FAILED = "FAILED";
    private static final String PUSH_SENT = "SENT";
    private static final String PUSH_DEAD = "DEAD";
    private static final int MAX_BATCH_SIZE = 500;
    private static final int MAX_ERROR_LENGTH = 512;

    private final NotificationMapper notificationMapper;
    private final PushProvider pushProvider;
    private final int maxAttempts;
    private final long retryDelaySeconds;

    public NotificationPushService(
            NotificationMapper notificationMapper,
            PushProvider pushProvider,
            @Value("${nexion.notification.push.max-attempts:3}") int maxAttempts,
            @Value("${nexion.notification.push.retry-delay-seconds:60}") long retryDelaySeconds) {
        this.notificationMapper = notificationMapper;
        this.pushProvider = pushProvider;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelaySeconds = Math.max(1, retryDelaySeconds);
    }

    public NotificationPushResult pushPending(int limit) {
        int normalizedLimit = Math.min(Math.max(1, limit), MAX_BATCH_SIZE);
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getIsDeleted, 0)
                .and(status -> status
                        .eq(Notification::getPushStatus, PUSH_PENDING)
                        .or()
                        .eq(Notification::getPushStatus, PUSH_FAILED))
                .and(due -> due
                        .isNull(Notification::getNextPushAt)
                        .or()
                        .le(Notification::getNextPushAt, now))
                .orderByAsc(Notification::getCreatedAt)
                .orderByAsc(Notification::getId)
                .last("LIMIT " + normalizedLimit);

        NotificationPushResult result = new NotificationPushResult();
        for (Notification notification : notificationMapper.selectList(wrapper)) {
            result.incrementScanned();
            pushOne(notification, result, now);
        }
        return result;
    }

    private void pushOne(Notification notification, NotificationPushResult result, LocalDateTime now) {
        int attempts = currentAttempts(notification) + 1;
        try {
            pushProvider.push(notification);
            Notification patch = new Notification();
            patch.setId(notification.getId());
            patch.setPushStatus(PUSH_SENT);
            patch.setPushAttempts(attempts);
            patch.setNextPushAt(null);
            patch.setLastPushError(null);
            patch.setPushedAt(now);
            notificationMapper.updateById(patch);
            result.incrementSent();
        } catch (RuntimeException ex) {
            Notification patch = new Notification();
            patch.setId(notification.getId());
            patch.setPushAttempts(attempts);
            patch.setLastPushError(truncate(ex.getMessage()));
            if (attempts >= maxAttempts) {
                patch.setPushStatus(PUSH_DEAD);
                patch.setNextPushAt(null);
                result.incrementDead();
            } else {
                patch.setPushStatus(PUSH_FAILED);
                patch.setNextPushAt(now.plusSeconds(retryDelaySeconds));
                result.incrementFailed();
            }
            notificationMapper.updateById(patch);
        }
    }

    private int currentAttempts(Notification notification) {
        return notification.getPushAttempts() == null ? 0 : Math.max(0, notification.getPushAttempts());
    }

    private String truncate(String message) {
        String value = message == null ? "Push failed" : message;
        if (value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
    }
}
