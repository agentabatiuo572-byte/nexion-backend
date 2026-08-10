package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.mapper.AdminPermissionMapper;
import ffdd.opsconsole.platform.dto.PermissionDictionaryView;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Select;

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

    @Test
    void legacyPermissionMetadataIsProjectedConservativelyForA6GrantEditing() {
        AdminPermissionMapper mapper = mock(AdminPermissionMapper.class);
        when(mapper.countPermissions(any(), any(), any())).thenReturn(1L);
        when(mapper.pagePermissions(any(), any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(List.of(new PermissionDictionaryView(
                        "legacy_sensitive_action", null, null, 0L, "", null, null, null)));
        when(mapper.selectPermissionDetail("legacy_sensitive_action"))
                .thenReturn(new PermissionDictionaryView(
                        "legacy_sensitive_action", null, null, 0L, "", null, null, null));
        OpsPlatformPermissionDictionaryService service = new OpsPlatformPermissionDictionaryService(mapper);

        var page = service.list(new ffdd.opsconsole.platform.dto.PermissionDictionaryQueryRequest(
                null, null, null, 1, 100));
        var detail = service.detail("legacy_sensitive_action");

        assertThat(page.getData().getRecords()).containsExactly(
                new PermissionDictionaryView(
                        "legacy_sensitive_action", "legacy_sensitive_action", "HIGH", null,
                        "未归类", 1, 0, ""));
        assertThat(detail.getData()).isEqualTo(page.getData().getRecords().get(0));
    }

    @Test
    void malformedPermissionMetadataIsNotNormalizedIntoAnApparentlyValidGrant() {
        AdminPermissionMapper mapper = mock(AdminPermissionMapper.class);
        when(mapper.countPermissions(any(), any(), any())).thenReturn(1L);
        when(mapper.pagePermissions(any(), any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(List.of(new PermissionDictionaryView(
                        "legacy_read", "Legacy", "owner", -1L, "A/A8", 2, -1, "/legacy")));
        OpsPlatformPermissionDictionaryService service = new OpsPlatformPermissionDictionaryService(mapper);

        var page = service.list(new ffdd.opsconsole.platform.dto.PermissionDictionaryQueryRequest(
                null, null, null, 1, 100));

        assertThat(page.getData().getRecords()).containsExactly(
                new PermissionDictionaryView(
                        "legacy_read", "Legacy", "OWNER", -1L, "A/A8", 2, -1, "/legacy"));
    }

    @Test
    void legacyCriticalPermissionsRemainConservativelyHighRisk() {
        AdminPermissionMapper mapper = mock(AdminPermissionMapper.class);
        when(mapper.countPermissions(any(), any(), any())).thenReturn(1L);
        when(mapper.pagePermissions(any(), any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(List.of(new PermissionDictionaryView(
                        "finance_d7_channel_toggle", "通道启停", "critical", 7L, "D / D7", 1, 1, "/d7")));
        OpsPlatformPermissionDictionaryService service = new OpsPlatformPermissionDictionaryService(mapper);

        var page = service.list(new ffdd.opsconsole.platform.dto.PermissionDictionaryQueryRequest(
                null, null, null, 1, 100));

        assertThat(page.getData().getRecords().get(0).permType()).isEqualTo("HIGH");
        assertThat(page.getData().getRecords().get(0).amplifies()).isEqualTo(1);
    }

    @Test
    void permissionTypeFiltersUseTheSameLegacyProjectionAsTheReturnedCatalog() throws Exception {
        String countSql = String.join("\n", AdminPermissionMapper.class
                .getMethod("countPermissions", String.class, String.class, String.class)
                .getAnnotation(Select.class).value());
        String pageSql = String.join("\n", AdminPermissionMapper.class
                .getMethod("pagePermissions", String.class, String.class, String.class, int.class, int.class)
                .getAnnotation(Select.class).value());

        assertThat(countSql).contains("RIGHT(LOWER(TRIM(p.permission_code)), 5) = '_read'");
        assertThat(countSql).contains("RIGHT(LOWER(TRIM(p.permission_code)), 6) = '_write'");
        assertThat(countSql).contains("UPPER(TRIM(p.perm_type)) = 'CRITICAL' THEN 'HIGH'");
        assertThat(pageSql).contains("RIGHT(LOWER(TRIM(p.permission_code)), 5) = '_read'");
        assertThat(pageSql).contains("RIGHT(LOWER(TRIM(p.permission_code)), 6) = '_write'");
        assertThat(pageSql).contains("UPPER(TRIM(p.perm_type)) = 'CRITICAL' THEN 'HIGH'");
    }
}
