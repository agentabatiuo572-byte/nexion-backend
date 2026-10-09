package ffdd.opsconsole.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.user.dto.UserQueryRequest;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MybatisUserOpsRepositoryPaginationTest {
    private final UserOpsMapper mapper = mock(UserOpsMapper.class);
    private final MybatisUserOpsRepository repository = new MybatisUserOpsRepository(mapper);

    @ParameterizedTest
    @CsvSource(value = {"42949674,NULL", "107374184,1", "85899347,NULL"}, nullValues = "NULL")
    void rejectsBothNegativeAndPositiveWrappedOffsetsBeforeMapper(int page, Integer size) {
        assertThatThrownBy(() -> repository.pageProfiles(UserQueryRequest.basic(null, null, null, page, size, null)))
                .isInstanceOf(BizException.class)
                .hasMessage("C1_PAGE_NUM_INVALID")
                .satisfies(error -> assertThat(((BizException) error).getCode()).isEqualTo(422));
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "1,NULL,50,0", "2,1,20,20", "2,201,200,200", "0,0,20,0",
            "42949673,NULL,50,2147483600", "107374183,1,20,2147483640", "10737419,200,200,2147483600"
    }, nullValues = "NULL")
    void preservesEffectiveSizeAndLargestRepresentableOffsets(int page, Integer size, int effectiveSize, int offset) {
        when(mapper.countUsersByQuery(any(), any(), isNull())).thenReturn(1L);

        var result = repository.pageProfiles(UserQueryRequest.basic(null, null, null, page, size, null));

        assertThat(result.getPageNum()).isEqualTo(Math.max(1, page));
        assertThat(result.getPageSize()).isEqualTo(effectiveSize);
        verify(mapper).pageUsers(any(), any(), eq(offset), eq(effectiveSize), isNull());
    }

    @ParameterizedTest
    @CsvSource(value = {"2,NULL,20,20", "2,1,1,1", "2147483647,1,1,2147483646"}, nullValues = "NULL")
    void retainsSupportSearchSizeContract(int page, Integer size, int effectiveSize, int offset) {
        when(mapper.countUsersByQuery(any(), any(), isNull())).thenReturn(1L);

        assertThat(repository.pageSupportProfiles(UserQueryRequest.basic(null, null, null, page, size, null))
                .getPageSize()).isEqualTo(effectiveSize);
        verify(mapper).pageUsers(any(), any(), eq(offset), eq(effectiveSize), isNull());
    }
}
