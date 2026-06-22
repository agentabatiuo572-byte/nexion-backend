package ffdd.opsconsole.user.domain;

import java.time.LocalDateTime;

public record UserNotificationView(
        String bizNo,
        String type,
        String title,
        String body,
        Boolean readFlag,
        String pushStatus,
        Integer pushAttempts,
        LocalDateTime nextPushAt,
        String lastPushError,
        LocalDateTime pushedAt,
        LocalDateTime createdAt) {
}
