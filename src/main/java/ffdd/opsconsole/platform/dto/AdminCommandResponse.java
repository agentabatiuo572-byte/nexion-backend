package ffdd.opsconsole.platform.dto;

import java.time.LocalDateTime;

public record AdminCommandResponse(
        String commandId,
        String action,
        String resourceType,
        String resourceId,
        boolean paramPersisted,
        LocalDateTime acceptedAt) {
}
