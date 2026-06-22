package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

public record SessionReplyTemplateView(
        String id,
        String type,
        String text,
        String status,
        LocalDateTime updatedAt) {
}
