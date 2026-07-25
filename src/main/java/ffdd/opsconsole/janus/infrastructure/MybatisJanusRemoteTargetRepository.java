package ffdd.opsconsole.janus.infrastructure;

import ffdd.opsconsole.janus.domain.JanusRemoteTargetCreateCommand;
import ffdd.opsconsole.janus.domain.JanusRemoteTargetRepository;
import ffdd.opsconsole.janus.domain.JanusRemoteTargetView;
import ffdd.opsconsole.janus.mapper.JanusRemoteTargetMapper;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisJanusRemoteTargetRepository implements JanusRemoteTargetRepository {
    private final JanusRemoteTargetMapper mapper;

    @PostConstruct
    void ensureSchema() {
        mapper.createTable();
    }

    @Override
    public List<JanusRemoteTargetView> list() {
        return mapper.list();
    }

    @Override
    public Optional<JanusRemoteTargetView> find(String key, int version) {
        return Optional.ofNullable(mapper.find(key, version));
    }

    @Override
    public JanusRemoteTargetView createVersion(JanusRemoteTargetCreateCommand command) {
        try {
            if (mapper.insertVersion(command) != 1) return null;
        } catch (DuplicateKeyException ignored) {
            return null;
        }
        return mapper.find(command.remoteTargetKey(), command.expectedLatestVersion() + 1);
    }

    @Override
    public JanusRemoteTargetView disableVersion(String key, int version, long catalogVersion,
                                                long expectedVersion, String operator) {
        return mapper.disableVersion(key, version, catalogVersion, expectedVersion, operator) == 1
                ? mapper.find(key, version)
                : null;
    }

    @Override
    public int cancelUnclaimedCommands(String key, int version, long catalogVersion) {
        mapper.cancelCommandRecords(key, version, catalogVersion);
        return mapper.cancelUnclaimedDevices(key, version, catalogVersion);
    }
}
