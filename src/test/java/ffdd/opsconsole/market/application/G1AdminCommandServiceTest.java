package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
import ffdd.opsconsole.market.mapper.StakingMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class G1AdminCommandServiceTest {
    private final OpsNexMarketService market = mock(OpsNexMarketService.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final StakingMapper mapper = mock(StakingMapper.class);
    private final G1AdminCommandService service = new G1AdminCommandService(market, idempotency, outbox, mapper);

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
    }

    @Test
    void parameterCommandUsesDurableIdempotencyAndPublishesAdminEvent() {
        NexMarketValueUpdateRequest request = new NexMarketValueUpdateRequest("13", "approved APY update", "superadmin");
        when(market.updateStakingPoolParam("g1-param-1", "usdt30d", "apy", request))
                .thenReturn(ApiResult.ok(Map.of("domain", "G1")));

        assertThat(service.updateParam("g1-param-1", "usdt30d", "apy", request).getCode()).isZero();

        verify(idempotency).execute(anyString(), anyString(), anyString(), any(), any());
        verify(outbox).publish(anyString(), anyString(), org.mockito.ArgumentMatchers.eq("admin.staking_pool_config_changed"), any());
    }

    @Test
    void killAtomicallyDisposesOpenPositionsAndPublishesStructuredEvent() {
        NexMarketValueUpdateRequest request = new NexMarketValueUpdateRequest(
                "true", "incident risk confirmed", "superadmin", null,
                "slash all open positions and notify every holder", "INCIDENT_RESPONSE");
        when(market.updateStakingPoolKillStatus("g1-kill-1", "usdt365d", request))
                .thenReturn(ApiResult.ok(Map.of("domain", "G1")));
        when(mapper.slashOpenPositionsByTier("usdt365d")).thenReturn(3);

        ApiResult<Map<String, Object>> result = service.kill("g1-kill-1", "usdt365d", request);

        assertThat(result.getCode()).isZero();
        assertThat(((Map<?, ?>) result.getData().get("killDisposition")).get("affectedPositions")).isEqualTo(3);
        verify(mapper).slashOpenPositionsByTier("usdt365d");
        verify(outbox).publish(anyString(), anyString(), org.mockito.ArgumentMatchers.eq("admin.staking_pool_killed"), any());
    }

    @Test
    void restorationUsesItsOwnIdempotentJ1CommandAndPublishesAuditEvent() {
        NexMarketValueUpdateRequest request = new NexMarketValueUpdateRequest(
                "false", "coverage and incident controls recovered", "superadmin", null,
                "risk review confirms the tier may safely reopen", "MANUAL_RISK_REVIEW");
        when(market.restoreStakingPool("g1-restore-1", "usdt30d", request))
                .thenReturn(ApiResult.ok(Map.of("domain", "G1")));

        assertThat(service.restore("g1-restore-1", "usdt30d", request).getCode()).isZero();

        verify(idempotency).execute(org.mockito.ArgumentMatchers.eq("ADMIN:G1:RESTORE:usdt30d"),
                org.mockito.ArgumentMatchers.eq("g1-restore-1"), anyString(),
                org.mockito.ArgumentMatchers.eq(ApiResult.class), any());
        verify(market).restoreStakingPool("g1-restore-1", "usdt30d", request);
        verify(outbox).publish(anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq("admin.staking_pool_restored"), any());
    }
}
