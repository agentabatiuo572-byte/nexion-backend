package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.mapper.AdminPermissionMapper;
import org.junit.jupiter.api.Test;

class OpsPlatformPermissionDictionaryServiceTest {
    @Test
    void nullQueryUsesBoundedDefaultsAndReturnsEmptyPage() {
        AdminPermissionMapper mapper = mock(AdminPermissionMapper.class);
        when(mapper.countPermissions(any(), any(), any())).thenReturn(0L);
        OpsPlatformPermissionDictionaryService service = new OpsPlatformPermissionDictionaryService(mapper);

        var result = service.list(null);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getPageNum()).isEqualTo(1);
        assertThat(result.getData().getPageSize()).isEqualTo(20);
        assertThat(result.getData().getRecords()).isEmpty();
    }

    @Test
    void pageNumberOverflowReturnsAnEmptyPageInsteadOfWrappingTheSqlOffset() {
        AdminPermissionMapper mapper = mock(AdminPermissionMapper.class);
        when(mapper.countPermissions(any(), any(), any())).thenReturn(1L);
        OpsPlatformPermissionDictionaryService service = new OpsPlatformPermissionDictionaryService(mapper);

        var result = service.list(new ffdd.opsconsole.platform.dto.PermissionDictionaryQueryRequest(
                null, null, null, Integer.MAX_VALUE, 100));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getRecords()).isEmpty();
        verify(mapper, never()).pagePermissions(any(), any(), any(), any(Integer.class), any(Integer.class));
    }
}
