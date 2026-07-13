package ffdd.opsconsole.janus.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record JanusStrategyUpsertRequest(
        String name,
        String description,
        Integer priority,
        String owner,
        JsonNode scope,
        JsonNode ruleTree,
        JsonNode action,
        JsonNode safeguards,
        JsonNode rollout,
        JsonNode healthConfig,
        String templateKey,
        Long expectedVersion,
        String reason) {
}
