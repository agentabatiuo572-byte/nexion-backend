package ffdd.opsconsole.janus.dto;

public record JanusStrategyActionRequest(
        String note,
        String reason,
        Integer targetVersion,
        Long expectedVersion,
        String dryRunId,
        String configHash) {
}
