package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

public record SupportFaqView(
        String id,
        String category,
        String question,
        String answer,
        String status,
        String surface,
        String language,
        Integer sortOrder,
        Integer version,
        LocalDateTime updatedAt) {
}
