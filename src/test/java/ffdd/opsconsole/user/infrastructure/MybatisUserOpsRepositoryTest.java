package ffdd.opsconsole.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

import ffdd.opsconsole.user.dto.UserQueryRequest;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MybatisUserOpsRepositoryTest {
    private final UserOpsMapper mapper = mock(UserOpsMapper.class);
    private final MybatisUserOpsRepository repository = new MybatisUserOpsRepository(mapper);

    @Test
    void passwordResetAcceptsMysqlDuplicateKeyUpdateAffectedRowCount() {
        when(mapper.markPasswordResetRequired(42L)).thenReturn(2);

        assertThat(repository.markPasswordResetRequired(42L, "unused-marker")).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"3775,3775", "13800138000,13800138000", "+86 138-0013-8000,8613800138000"})
    void supportSearchNormalizesPhoneForBothCountAndRows(String keyword, String digits) {
        when(mapper.countUsersByQuery(any(), any(), eq(digits))).thenReturn(17L);
        when(mapper.pageUsers(any(), any(), eq(8), eq(8), eq(digits))).thenReturn(List.of());
        var result = repository.pageSupportProfiles(UserQueryRequest.basic(keyword, null, null, 2, 8, null));
        assertThat(result.getTotal()).isEqualTo(17);
        assertThat(result.getPageNum()).isEqualTo(2);
        verify(mapper).pageUsers(any(), any(), eq(8), eq(8), eq(digits));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"   ", "377", "U60723152670", "Alice", "1234567890123456", "3775%", "3775' OR 1=1"})
    void supportSearchDoesNotTurnTextOrInvalidPhoneIntoPhoneMatching(String keyword) {
        repository.pageSupportProfiles(UserQueryRequest.basic(keyword, null, null, 1, 8, null));
        verify(mapper).countUsersByQuery(any(), any(), isNull());
    }

    @Test
    void c1CannotEnablePhoneMatching() {
        repository.pageProfiles(UserQueryRequest.basic("3775", null, null, 1, 8, null));
        verify(mapper).countUsersByQuery(any(), any(), isNull());
    }
}
