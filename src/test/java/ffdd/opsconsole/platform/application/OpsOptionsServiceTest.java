package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import ffdd.opsconsole.auth.mapper.AdminRolePermissionMapper;
import ffdd.opsconsole.platform.dto.AdminOption;
import ffdd.opsconsole.platform.infrastructure.AdminRoleOptionEntity;
import ffdd.opsconsole.platform.mapper.OpsOptionsMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OpsOptionsServiceTest {
    private final OpsOptionsService service = new OpsOptionsService(mock(OpsOptionsMapper.class), mock(AdminRolePermissionMapper.class));

    @BeforeAll
    static void initializeMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AdminRoleOptionEntity.class);
    }

    @Test
    void novaTemplateCtasComeFromDynamicOptionsEndpoint() {
        List<AdminOption> options = service.options("content", "nova-template-ctas").getData();

        assertThat(options)
                .extracting(AdminOption::value)
                .contains("NONE", "/me/weekly", "/devices", "/staking", "/team", "/earn", "/support");
    }

    @Test
    void normalizesDomainAndNameBeforeResolvingOptions() {
        List<AdminOption> options = service.options(" Content ", "nova_template_ctas").getData();

        assertThat(options)
                .extracting(AdminOption::value)
                .contains("/devices", "/support");
    }

    @Test
    void rolesPreferDatabaseValuesAndFilterBlankRows() {
        OpsOptionsMapper mapper = mock(OpsOptionsMapper.class);
        OpsOptionsService databaseBackedService = new OpsOptionsService(mapper, mock(AdminRolePermissionMapper.class));
        when(mapper.selectList(any())).thenReturn(List.of(
                role("Finance Reviewer"),
                role("Risk Analyst"),
                role("  ")));

        List<AdminOption> options = databaseBackedService.options("platform", "roles").getData();

        assertThat(options)
                .extracting(AdminOption::value)
                .containsExactly("Finance Reviewer", "Risk Analyst");
    }

    @Test
    void rolesFallbackWhenDatabaseQueryFails() {
        OpsOptionsMapper mapper = mock(OpsOptionsMapper.class);
        OpsOptionsService fallbackService = new OpsOptionsService(mapper, mock(AdminRolePermissionMapper.class));
        when(mapper.selectList(any())).thenThrow(new IllegalStateException("db unavailable"));

        List<AdminOption> options = fallbackService.options("platform", "roles").getData();

        assertThat(options)
                .extracting(AdminOption::value)
                .containsExactly(
                        "SUPER_ADMIN",
                        "CONFIG_ADMIN",
                        "FINANCE",
                        "RISK",
                        "CONTENT",
                        "GROWTH",
                        "SUPPORT",
                        "AUDITOR");
    }

    @Test
    void datacentersPreferDatabaseValues() {
        OpsOptionsMapper mapper = mock(OpsOptionsMapper.class);
        OpsOptionsService databaseBackedService = new OpsOptionsService(mapper, mock(AdminRolePermissionMapper.class));
        when(mapper.datacenters()).thenReturn(List.of("HK-1", "SG-1", "US-1"));

        List<AdminOption> options = databaseBackedService.options("devices", "datacenters").getData();

        assertThat(options)
                .extracting(AdminOption::value)
                .containsExactly("HK-1", "SG-1", "US-1");
    }

    @Test
    void datacentersReturnEmptyWhenDatabaseReturnsNoRows() {
        OpsOptionsMapper mapper = mock(OpsOptionsMapper.class);
        OpsOptionsService fallbackService = new OpsOptionsService(mapper, mock(AdminRolePermissionMapper.class));
        when(mapper.datacenters()).thenReturn(List.of());

        List<AdminOption> options = fallbackService.options("devices", "datacenters").getData();

        assertThat(options)
                .extracting(AdminOption::value)
                .isEmpty();
    }

    @Test
    void supportBusinessOptionsComeFromDatabaseRows() {
        OpsOptionsMapper mapper = mock(OpsOptionsMapper.class);
        OpsOptionsService databaseBackedService = new OpsOptionsService(mapper, mock(AdminRolePermissionMapper.class));
        when(mapper.supportAgents()).thenReturn(List.of(new OpsOptionsMapper.OptionRow("alice", "101")));
        when(mapper.transferTargets()).thenReturn(List.of(new OpsOptionsMapper.OptionRow("alice · support", "101")));
        when(mapper.sessionReplyTemplates()).thenReturn(List.of(new OpsOptionsMapper.OptionRow("KYC · tpl-1", "reply body")));
        when(mapper.supportSlaQueues()).thenReturn(List.of(new OpsOptionsMapper.OptionRow("支付台", "支付台")));
        when(mapper.supportSlaEscalations()).thenReturn(List.of(new OpsOptionsMapper.OptionRow("D2 withdrawal review", "D2 withdrawal review")));

        assertThat(databaseBackedService.options("support", "support-agents").getData())
                .extracting(AdminOption::value)
                .containsExactly("101");
        assertThat(databaseBackedService.options("support", "transfer-targets").getData())
                .extracting(AdminOption::label)
                .containsExactly("alice · support");
        assertThat(databaseBackedService.options("support", "support-reply-templates").getData())
                .extracting(AdminOption::value)
                .containsExactly("reply body");
        assertThat(databaseBackedService.options("support", "support-sla-queues").getData())
                .extracting(AdminOption::value)
                .containsExactly("支付台");
        assertThat(databaseBackedService.options("support", "support-sla-escalations").getData())
                .extracting(AdminOption::value)
                .containsExactly("D2 withdrawal review");
    }

    @Test
    void unknownOptionReturnsNotFoundInsteadOfLocalDefaults() {
        ApiResult<List<AdminOption>> result = service.options("devices", "unknown-select");

        assertThat(result.getCode()).isEqualTo(404);
        assertThat(result.getMessage()).isEqualTo("OPTION_NOT_FOUND");
        assertThat(result.getData()).isNull();
    }

    @Test
    void deviceCatalogFormsUseBackendOptionsInsteadOfFrontendDefaults() {
        assertThat(service.options("devices", "sku-tiers").getData())
                .extracting(AdminOption::value)
                .containsExactly("Entry", "Pro", "Flagship", "Share");
        assertThat(service.options("devices", "sku-generations").getData())
                .extracting(AdminOption::value)
                .containsExactly("1", "2", "3");
        assertThat(service.options("devices", "sku-lifecycles").getData())
                .extracting(AdminOption::value)
                .containsExactly("active", "legacy");
        assertThat(service.options("devices", "sku-unlock-phases").getData())
                .extracting(AdminOption::value)
                .containsExactly("P1", "P2", "P3", "P4", "P5", "P6");
    }

    @Test
    void deviceTaskFormsUseBackendOptionsInsteadOfFrontendDefaults() {
        assertThat(service.options("devices", "task-units").getData())
                .extracting(AdminOption::value)
                .containsExactly("/job", "/1k", "/min");
        assertThat(service.options("devices", "task-requirements").getData())
                .extracting(AdminOption::value)
                .containsExactly("S1+", "需 NexionBox Pro", "需 NexionRack");
    }

    @Test
    void riskScoringSourcesComeFromBackendOptionsEndpoint() {
        assertThat(service.options("risk", "scoring-sources").getData())
                .extracting(AdminOption::value)
                .containsExactly("全部启用", "停用 C4 实名维度", "停用 K2 套利维度", "停用异常行为维度");
    }

    @Test
    void biReportStatusBucketsComeFromBackendOptionsEndpoint() {
        List<AdminOption> options = service.options("bi", "report-statuses").getData();

        assertThat(options)
                .extracting(AdminOption::value)
                .containsExactly("ALL", "PENDING_CONFIRM,PENDING_SPLIT_CONFIRM", "GENERATING", "READY");
        assertThat(options.get(1).meta())
                .containsEntry("statuses", List.of("PENDING_CONFIRM", "PENDING_SPLIT_CONFIRM"));
    }

    private AdminRoleOptionEntity role(String roleName) {
        AdminRoleOptionEntity entity = new AdminRoleOptionEntity();
        entity.setRoleName(roleName);
        return entity;
    }
}
