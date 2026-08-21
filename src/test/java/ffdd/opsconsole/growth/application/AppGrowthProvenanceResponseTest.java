package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.VoucherGrantFacade;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppGrowthProvenanceResponseTest {
    private final AppGrowthEngagementMapper mapper = mock(AppGrowthEngagementMapper.class);
    private final AppGrowthEngagementService service = new AppGrowthEngagementService(
            mapper,
            mock(VoucherGrantFacade.class),
            mock(GrowthRhythmFacade.class),
            mock(TreasuryCoverageFacade.class),
            mock(AdminIdempotencyService.class),
            mock(AuditLogService.class),
            mock(EventOutboxService.class),
            null,
            null,
            null,
            java.util.Optional.empty(),
            null);

    @Test
    void productionPointsStateCarriesCanonicalEnvironmentFence() {
        when(mapper.findActiveUser(42L)).thenReturn(42L);
        when(mapper.pointState(eq(42L), any(LocalDate.class))).thenReturn(Map.of("checkedInToday", false));

        ApiResult<Map<String, Object>> response = service.pointState(42L);

        assertThat(response.getData())
                .containsEntry("serverCanonical", true)
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "");
    }
}
