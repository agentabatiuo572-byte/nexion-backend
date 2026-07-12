package ffdd.opsconsole.content.dto;

import java.util.List;

public record NovaSocialEventSyncRequest(
        List<String> sourceTypes,
        Integer lookbackHours,
        Integer ttlHours,
        String operator,
        String reason) {
}
