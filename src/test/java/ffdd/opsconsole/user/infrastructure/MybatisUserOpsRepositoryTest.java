package ffdd.opsconsole.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.user.mapper.UserOpsMapper;
import org.junit.jupiter.api.Test;

class MybatisUserOpsRepositoryTest {
    private final UserOpsMapper mapper = mock(UserOpsMapper.class);
    private final MybatisUserOpsRepository repository = new MybatisUserOpsRepository(mapper);

    @Test
    void passwordResetAcceptsMysqlDuplicateKeyUpdateAffectedRowCount() {
        when(mapper.markPasswordResetRequired(42L)).thenReturn(2);

        assertThat(repository.markPasswordResetRequired(42L, "unused-marker")).isTrue();
    }
}
