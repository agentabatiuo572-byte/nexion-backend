package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.canonical.AppProductCatalogService;
import ffdd.opsconsole.team.domain.VRankEvaluationSnapshot;
import ffdd.opsconsole.team.domain.VRankPerformanceRepository;
import ffdd.opsconsole.team.mapper.AppTeamQuotaMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppTeamQuotaServiceTest {
    @Test
    void projectsServerFactsAndRejectsMockCatalogInProduction() {
        var mapper = mock(AppTeamQuotaMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamQuotaMapper.UserScope(0, "V5"));
        when(mapper.quotaRows()).thenReturn(List.of(new AppTeamQuotaMapper.QuotaRow(
                "PRO", "stellarbox-pro", "Pro", 3, BigDecimal.ZERO, 10, "ALL", 2L)));
        when(mapper.activeDirect(7L)).thenReturn(2L);
        var performance = mock(VRankPerformanceRepository.class);
        when(performance.computeSnapshot(7L)).thenReturn(new VRankEvaluationSnapshot(
                BigDecimal.ZERO, new BigDecimal("150000"), 3, Map.of()));
        var catalog = mock(AppProductCatalogService.class);
        when(catalog.catalog(7L)).thenReturn(ApiResult.ok(Map.of(
                "source", "mock", "products", List.of(Map.of("id", "stellarbox-pro", "price", new BigDecimal("899"), "available", true)))));
        var service = new AppTeamQuotaService(mapper, performance, catalog, new MockEnvironment());

        var result = service.snapshot(7L);

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("TEAM_QUOTA_CATALOG_NOT_READY");
    }

    @Test
    void sandboxQuotaFailsClosedBecauseUsageRowsAreNotRunScoped() {
        var mapper = mock(AppTeamQuotaMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamQuotaMapper.UserScope(1, "V5"));
        var environment = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", "run-0001");
        environment.setActiveProfiles("test");
        var service = new AppTeamQuotaService(mapper, mock(VRankPerformanceRepository.class),
                mock(AppProductCatalogService.class), environment);

        var result = service.snapshot(7L);

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("TEAM_QUOTA_RUN_SCOPE_UNAVAILABLE");
    }

    @Test
    void developmentAllowsAnyActiveDevelopmentAccountAndCanonicalCatalog() {
        var mapper = mock(AppTeamQuotaMapper.class);
        when(mapper.userScope(7L)).thenReturn(new AppTeamQuotaMapper.UserScope(1, "V5"));
        when(mapper.quotaRows()).thenReturn(List.of(new AppTeamQuotaMapper.QuotaRow(
                "PRO", "stellarbox-pro", "Pro", 3, BigDecimal.ZERO, 10, "ALL", 2L)));
        when(mapper.activeDirect(7L)).thenReturn(2L);
        var performance = mock(VRankPerformanceRepository.class);
        when(performance.computeSnapshot(7L)).thenReturn(new VRankEvaluationSnapshot(
                BigDecimal.ZERO, new BigDecimal("150000"), 8, Map.of()));
        var catalog = mock(AppProductCatalogService.class);
        when(catalog.catalog(7L)).thenReturn(ApiResult.ok(Map.of(
                "source", "nx_product", "products", List.of(Map.of(
                        "id", "stellarbox-pro", "price", new BigDecimal("899"), "available", true)))));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        var result = new AppTeamQuotaService(mapper, performance, catalog, environment).snapshot(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "PRODUCTION").containsEntry("runId", "");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tiers = (List<Map<String, Object>>) result.getData().get("tiers");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conditions = (List<Map<String, Object>>) tiers.get(0).get("conditions");
        assertThat(conditions.get(0)).containsEntry("kind", "directRefs")
                .containsEntry("required", 3).containsEntry("current", 2L);
    }
}
