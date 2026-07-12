package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

public record NovaSocialRenderedEventView(
        Long id,
        String eventType,
        String sourceEventId,
        String language,
        String title,
        String body,
        LocalDateTime expiresAt) {
}
