package ffdd.opsconsole.content.dto;

import java.util.List;
import java.util.Map;

public record SupportLoadRebalanceRequest(
        List<Map<String, Object>> agents,
        String operator,
        String reason) {
}
