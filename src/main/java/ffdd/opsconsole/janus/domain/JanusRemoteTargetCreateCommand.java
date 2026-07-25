package ffdd.opsconsole.janus.domain;

public record JanusRemoteTargetCreateCommand(
        String remoteTargetKey,
        String label,
        String url,
        String origin,
        String ownerId,
        int expectedLatestVersion,
        String reason,
        String impact,
        String operator) {
}
