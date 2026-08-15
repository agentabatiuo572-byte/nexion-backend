package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.facade.VoucherGrantFacade;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper.WheelEvent;
import ffdd.opsconsole.growth.mapper.AppGrowthWheelMapper.WheelTier;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppGrowthWheelStateServiceTest {
    private final AppGrowthWheelMapper mapper = mock(AppGrowthWheelMapper.class);
    private final AppGrowthWheelService service = new AppGrowthWheelService(
            mapper, mock(VoucherGrantFacade.class), mock(TreasuryCoverageFacade.class),
            mock(AdminIdempotencyService.class), mock(AuditLogService.class),
            mock(EventOutboxService.class), null);

    @Test
    void stateReturnsServerSegmentsTicketCountsAndHistoryWithoutProbabilityOrGuardFacts() {
        when(mapper.findActiveUser(42L)).thenReturn(42L);
        when(mapper.findOpenWheelEvent("evt-spring-spin"))
                .thenReturn(new WheelEvent(8L, "evt-spring-spin"));
        when(mapper.countDailySpin(eq(8L), eq(42L), any())).thenReturn(0);
        when(mapper.countAvailableTickets(42L)).thenReturn(2);
        when(mapper.listActiveTiers()).thenReturn(List.of(
                new WheelTier(1L, "comfort-nex-5", "+5 NEX", new BigDecimal("38.0000"), false,
                        "nex", new BigDecimal("5"), null, 0)));
        when(mapper.listWheelHistory(42L, "evt-spring-spin", 20)).thenReturn(List.of(
                Map.of("spinId", "SPIN-1", "spinDate", "2026-08-15", "sourceType", "DAILY",
                        "tierId", 1L, "rewardType", "NEX", "rewardAmount", new BigDecimal("5"),
                        "rewardName", "+5 NEX", "downgraded", false, "downgradeReason", "NONE",
                        "awardedAt", "2026-08-15T01:02:03")));

        ApiResult<Map<String, Object>> result = service.state(42L, "evt-spring-spin");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("freeAvailable", true)
                .containsEntry("bonusTickets", 2)
                .containsKey("segments").containsKey("history");
        assertThat(result.getData()).doesNotContainKeys("probabilities", "probabilityPct", "budget", "guards");
        Map<?, ?> segment = (Map<?, ?>) ((List<?>) result.getData().get("segments")).get(0);
        assertThat(segment.keySet().contains("probabilityPct")).isFalse();
        verify(mapper, never()).lockGuardValue("budget");
    }
}
