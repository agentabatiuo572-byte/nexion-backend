package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

public record SessionScriptView(
        String id,
        String scriptGroup,
        String text,
        String ctaPath,
        String status,
        String audience,
        LocalDateTime updatedAt) {
}
