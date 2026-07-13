package ffdd.opsconsole.janus.domain;

import com.fasterxml.jackson.databind.JsonNode;

public record JanusStrategyVersionView(
        int version,
        String note,
        String actorId,
        Long createdAt,
        JsonNode ruleTree,
        JsonNode action,
        JsonNode snapshot,
        String configHash) {
}
