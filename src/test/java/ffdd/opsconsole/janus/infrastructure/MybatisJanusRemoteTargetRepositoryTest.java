package ffdd.opsconsole.janus.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.janus.domain.JanusRemoteTargetCreateCommand;
import ffdd.opsconsole.janus.domain.JanusRemoteTargetView;
import ffdd.opsconsole.janus.mapper.JanusRemoteTargetMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class MybatisJanusRemoteTargetRepositoryTest {
    private final JanusRemoteTargetMapper mapper = mock(JanusRemoteTargetMapper.class);
    private final MybatisJanusRemoteTargetRepository repository =
            new MybatisJanusRemoteTargetRepository(mapper);

    @Test
    void bootstrapsAndReadsPersistedRows() {
        JanusRemoteTargetView target = target();
        when(mapper.list()).thenReturn(List.of(target));
        when(mapper.find("finance-main", 1)).thenReturn(target);
        repository.ensureSchema();
        assertThat(repository.list()).containsExactly(target);
        assertThat(repository.find("finance-main", 1)).contains(target);
        verify(mapper).createTable();
    }

    @Test
    void createReturnsOnlyTheNewImmutableVersionAndFailsClosedOnRaces() {
        JanusRemoteTargetCreateCommand command = command();
        when(mapper.insertVersion(command)).thenReturn(1);
        when(mapper.find("finance-main", 1)).thenReturn(target());
        assertThat(repository.createVersion(command)).isEqualTo(target());

        when(mapper.insertVersion(command)).thenReturn(0);
        assertThat(repository.createVersion(command)).isNull();

        doThrow(new DuplicateKeyException("version race")).when(mapper).insertVersion(command);
        assertThat(repository.createVersion(command)).isNull();
    }

    @Test
    void disableAndCancellationKeepCommandAndDeviceUpdatesTogether() {
        when(mapper.disableVersion("finance-main", 1, 1, 7, "operator")).thenReturn(1);
        when(mapper.find("finance-main", 1)).thenReturn(target());
        assertThat(repository.disableVersion("finance-main", 1, 1, 7, "operator")).isEqualTo(target());

        when(mapper.disableVersion("finance-main", 1, 1, 8, "operator")).thenReturn(0);
        assertThat(repository.disableVersion("finance-main", 1, 1, 8, "operator")).isNull();

        when(mapper.cancelUnclaimedDevices("finance-main", 1, 1)).thenReturn(3);
        assertThat(repository.cancelUnclaimedCommands("finance-main", 1, 1)).isEqualTo(3);
        verify(mapper).cancelCommandRecords("finance-main", 1, 1);
    }

    private JanusRemoteTargetCreateCommand command() {
        return new JanusRemoteTargetCreateCommand("finance-main", "财务主站",
                "https://approved.example/app", "https://approved.example", "risk-owner",
                0, "批准新版本用于灰度切换", "两条策略待迁移且已有回滚方案", "operator");
    }

    private JanusRemoteTargetView target() {
        return new JanusRemoteTargetView(1, "finance-main", 1, "ACTIVE", "财务主站",
                "https://approved.example/app", "https://approved.example", "ADMIN", "risk-owner",
                1, 2, "operator", "批准新版本用于灰度切换", "影响已确认", 7, 0, 0, 0);
    }
}
