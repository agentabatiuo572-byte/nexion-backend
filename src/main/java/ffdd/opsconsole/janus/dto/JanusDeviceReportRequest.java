package ffdd.opsconsole.janus.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record JanusDeviceReportRequest(
        String reportId,
        String deviceId,
        Long reportedAt,
        Long firstSeenAt,
        Long installAt,
        String inviteCode,
        String channel,
        String cohortId,
        String reportedStatus,
        Boolean activated,
        String ua,
        String platform,
        String model,
        String osName,
        String browser,
        Integer maturityScore,
        Integer recommendationScore,
        Integer environmentRiskScore,
        Integer priorityScore,
        JsonNode maturity,
        JsonNode environment,
        String hitStrategy,
        Integer hitStrategyVersion,
        JsonNode latestDecision,
        JsonNode latestSession,
        JsonNode tags) {
}
