package ffdd.opsconsole.janus.domain;

import java.util.List;
import java.util.Optional;

public interface JanusRemoteTargetRepository {
    List<JanusRemoteTargetView> list();

    Optional<JanusRemoteTargetView> find(String key, int version);

    JanusRemoteTargetView createVersion(JanusRemoteTargetCreateCommand command);

    JanusRemoteTargetView disableVersion(String key, int version, long catalogVersion,
                                         long expectedVersion, String operator);

    int cancelUnclaimedCommands(String key, int version, long catalogVersion);

    /** Legacy test/diagnostic surface only; runtime binding must use find(key, version). */
    default boolean hasKey(String key) {
        return list().stream().anyMatch(target -> target.remoteTargetKey().equals(key));
    }

    /** Legacy test/diagnostic surface only; runtime binding must use exact immutable identity. */
    default boolean hasActiveKey(String key) {
        return list().stream().anyMatch(target -> target.remoteTargetKey().equals(key)
                && "ACTIVE".equals(target.status()));
    }
}
