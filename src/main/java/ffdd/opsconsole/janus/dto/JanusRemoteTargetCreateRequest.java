package ffdd.opsconsole.janus.dto;

public record JanusRemoteTargetCreateRequest(
        String remoteTargetKey,
        String label,
        String url,
        String ownerId,
        Integer expectedLatestVersion,
        String reason,
        String impact) {
}
