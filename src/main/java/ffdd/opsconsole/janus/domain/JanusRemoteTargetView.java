package ffdd.opsconsole.janus.domain;

public record JanusRemoteTargetView(
        long catalogVersion,
        String remoteTargetKey,
        int remoteTargetVersion,
        String status,
        String label,
        String url,
        String origin,
        String source,
        String ownerId,
        long createdAt,
        long updatedAt,
        String updatedBy,
        String changeReason,
        String impact,
        long lockVersion,
        int strategyCount,
        int pendingCommandCount,
        int cancelledCommandCount) {
}
