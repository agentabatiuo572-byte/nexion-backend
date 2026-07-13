package ffdd.opsconsole.janus.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record JanusStrategyView(
        String strategyId,
        String name,
        String description,
        String status,
        int version,
        int priority,
        String owner,
        JsonNode scope,
        JsonNode ruleTree,
        JsonNode action,
        JsonNode safeguards,
        JsonNode rollout,
        JsonNode healthConfig,
        String templateKey,
        List<JanusStrategyVersionView> versions,
        Long createdAt,
        Long publishedAt,
        long lockVersion) {
}
