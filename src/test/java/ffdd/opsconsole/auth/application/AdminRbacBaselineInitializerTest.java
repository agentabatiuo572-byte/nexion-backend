package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.mapper.AdminRolePermissionMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AdminRbacBaselineInitializerTest {

    private final AdminRolePermissionMapper mapper = mock(AdminRolePermissionMapper.class);
    private final AdminRbacBaselineInitializer initializer = new AdminRbacBaselineInitializer(mapper);

    @Test
    void ensuresClassicRolesWithoutReintroducingLegacyPermissionTruth() {
        when(mapper.countActiveClassicPermissions()).thenReturn(273L);
        when(mapper.countActiveSuperAdminClassicPermissions()).thenReturn(273L);

        initializer.ensureBaseline();

        verify(mapper).ensureRole("SUPER_ADMIN", "超级管理员", "平台全域管理员");
        verify(mapper).ensureRole("CONFIG_ADMIN", "配置运营", "平台配置与系统参数管理员");
        verify(mapper).ensureRole("FINANCE", "财务", "资金、账务与提现审核");
        verify(mapper).ensureRole("RISK", "风控", "风控、KYC 与紧急处置");
        verify(mapper).ensureRole("CONTENT", "内容运营", "内容、公告与披露管理");
        verify(mapper).ensureRole("GROWTH", "增长运营", "增长、设备与网络运营");
        verify(mapper).ensureRole("SUPPORT", "客服", "客服中心全局后台角色;主管、专属、通用在 M1 坐席业务表内配置");
        verify(mapper).ensureRole("AUDITOR", "只读审计", "审计与合规只读观察");
        verify(mapper, never()).ensurePermission(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void failsFastInsteadOfStartingAnAdminConsoleWithNoClassicGrants() {
        when(mapper.countActiveClassicPermissions()).thenReturn(0L);

        assertThatThrownBy(initializer::ensureBaseline)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RBAC_CLASSIC_MIGRATION_REQUIRED");
    }

    @Test
    void rejectsPreviousBaselineThatLacksExperimentManagePermission() {
        when(mapper.countActiveClassicPermissions()).thenReturn(272L);
        when(mapper.countActiveSuperAdminClassicPermissions()).thenReturn(272L);

        assertThatThrownBy(initializer::ensureBaseline)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RBAC_CLASSIC_MIGRATION_REQUIRED");
    }

    @Test
    void classicSeedGrantsDedicatedExperimentPermissionOnlyThroughSuperAdminAndContentBaselines() throws Exception {
        String permissions = Files.readString(Path.of("scripts/rbac-classic-seed/IJ.sql"), StandardCharsets.UTF_8);
        String grants = Files.readString(Path.of("scripts/rbac-classic-seed/02-role-permission-seed.sql"), StandardCharsets.UTF_8);

        assertThat(permissions).contains("content_i1_experiment_manage");
        assertThat(grants).contains("r.role_code='SUPER_ADMIN'", "r.role_code='CONTENT'");
        assertThat(grants).doesNotContain("r.role_code='GROWTH' AND p.permission_code LIKE 'content_%'");
    }
}
