package ffdd.opsconsole.janus.dto;

public record JanusRemoteTargetDisableRequest(
        Long expectedVersion,
        Long expectedCatalogVersion,
        String reason,
        String impact) {
}
