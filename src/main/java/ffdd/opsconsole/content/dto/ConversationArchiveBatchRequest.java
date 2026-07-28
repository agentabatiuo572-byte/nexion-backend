package ffdd.opsconsole.content.dto;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public record ConversationArchiveBatchRequest(
        List<String> conversationNos,
        Map<String, Long> expectedVersions,
        String reason,
        String operator) {
    public ConversationArchiveBatchRequest(List<String> conversationNos, String reason, String operator) {
        this(conversationNos, zeroVersions(conversationNos), reason, operator);
    }

    private static Map<String, Long> zeroVersions(List<String> ids) {
        Map<String, Long> versions = new LinkedHashMap<>();
        if (ids != null) ids.forEach(id -> versions.put(id, 0L));
        return versions;
    }
}
