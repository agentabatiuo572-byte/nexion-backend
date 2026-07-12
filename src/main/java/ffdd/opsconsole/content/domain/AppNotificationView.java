package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

public record AppNotificationView(
        Long id,
        String kind,
        String priority,
        String title,
        String body,
        String ctaLabel,
        String ctaHref,
        LocalDateTime createdAt,
        LocalDateTime readAt) {
}
