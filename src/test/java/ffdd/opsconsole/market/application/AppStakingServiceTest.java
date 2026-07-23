package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.facade.RiskDisclosureGateFacade;
import ffdd.opsconsole.market.mapper.AppStakingMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppStakingServiceTest {
    private final AppStakingMapper mapper = mock(AppStakingMapper.class);
    private final RiskDisclosureGateFacade disclosureGate = mock(RiskDisclosureGateFacade.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-22T03:00:00Z"), ZoneOffset.UTC);
    private final AppStakingService service = new AppStakingService(
            mapper, disclosureGate, config, idempotency, outbox, audit, clock);

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        when(mapper.listCanonicalProducts()).thenReturn(List.of(product()));
        when(mapper.lockProductByTier("usdt30d")).thenReturn(product());
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(mapper.lockWalletBalance(42L)).thenReturn(new BigDecimal("1000"));
        when(mapper.walletBalance(42L)).thenReturn(new BigDecimal("900"));
        when(mapper.listUserPositions(42L)).thenReturn(List.of());
        when(mapper.debitWallet(42L, new BigDecimal("100.000000"))).thenReturn(1);
        when(mapper.insertPosition(any())).thenReturn(1);
        when(mapper.insertLedger(any())).thenReturn(1);
        when(mapper.userAttribution(42L)).thenReturn(new AppStakingMapper.UserAttribution("P1", 3, "2026-W30"));
        when(disclosureGate.checkUserGate(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq("staking"), anyString())).thenReturn(ApiResult.ok(null));
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
    }

    @Test
    void publicPoolsUseActiveProductAsTheDefaultSaleTruth() {
        ApiResult<java.util.Map<String, Object>> result = service.pools();

        assertThat(result.getCode()).isZero();
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> pools = (List<java.util.Map<String, Object>>) result.getData().get("pools");
        assertThat(pools).singleElement().satisfies(pool -> assertThat(pool)
                .containsEntry("tierKey", "usdt30d")
                .containsEntry("enabled", true)
                .containsEntry("killed", false));
    }

    @Test
    void openingStakeDebitsWalletPersistsLockedSnapshotAndPublishesEvent() {
        ApiResult<java.util.Map<String, Object>> result = service.open(
                42L, "open-1", new AppStakingService.OpenRequest("usdt30d", new BigDecimal("100")));

        assertThat(result.getCode()).isZero();
        verify(disclosureGate).checkUserGate(42L, "staking", "open-1");
        verify(mapper).debitWallet(42L, new BigDecimal("100.000000"));
        verify(mapper).insertPosition(any(AppStakingMapper.PositionWrite.class));
        verify(mapper).insertLedger(any(AppStakingMapper.LedgerWrite.class));
        verify(outbox).publishUserEvent(anyString(), anyString(), org.mockito.ArgumentMatchers.eq("staking.opened"),
                org.mockito.ArgumentMatchers.eq(42L), anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString(), any());
        verify(audit).recordRequiredForTrustedActor(any());
    }

    @Test
    void sameKeyCanSucceedAfterDisclosureIsAcknowledgedWithoutDoubleDebit() {
        when(disclosureGate.checkUserGate(42L, "staking", "open-after-ack"))
                .thenReturn(ApiResult.fail(409, "RISK_DISCLOSURE_ACK_REQUIRED"), ApiResult.ok(null));

        ApiResult<java.util.Map<String, Object>> blocked = service.open(
                42L, "open-after-ack", new AppStakingService.OpenRequest("usdt30d", new BigDecimal("100")));

        assertThat(blocked.getCode()).isEqualTo(409);
        assertThat(blocked.getMessage()).isEqualTo("RISK_DISCLOSURE_ACK_REQUIRED");
        verify(mapper, never()).debitWallet(any(), any());
        verify(mapper, never()).insertPosition(any());
        verify(mapper, never()).insertLedger(any());

        ApiResult<java.util.Map<String, Object>> opened = service.open(
                42L, "open-after-ack", new AppStakingService.OpenRequest("usdt30d", new BigDecimal("100")));

        assertThat(opened.getCode()).isZero();

        verify(disclosureGate, org.mockito.Mockito.times(2)).checkUserGate(42L, "staking", "open-after-ack");
        verify(mapper).debitWallet(42L, new BigDecimal("100.000000"));
        verify(mapper).insertPosition(any());
        verify(mapper).insertLedger(any());
        verify(outbox).publishUserEvent(anyString(), anyString(), anyString(), any(), anyString(), any(), anyString(), any());
        verify(audit).recordRequiredForTrustedActor(any());
    }

    @Test
    void acknowledgedDisclosureIsPerUserAndDoesNotTurnIntoAGlobalStop() {
        when(mapper.controlValue("disclosure.gate.staking")).thenReturn("true");
        when(disclosureGate.checkUserGate(42L, "staking", "open-acknowledged")).thenReturn(ApiResult.ok(null));

        ApiResult<java.util.Map<String, Object>> pools = service.pools();
        ApiResult<java.util.Map<String, Object>> opened = service.open(
                42L, "open-acknowledged", new AppStakingService.OpenRequest("usdt30d", new BigDecimal("100")));

        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> rows = (List<java.util.Map<String, Object>>) pools.getData().get("pools");
        assertThat(rows).singleElement().satisfies(pool -> assertThat(pool).containsEntry("enabled", true));
        assertThat(opened.getCode()).isZero();
        verify(disclosureGate).checkUserGate(42L, "staking", "open-acknowledged");
        verify(mapper).debitWallet(42L, new BigDecimal("100.000000"));
    }

    @Test
    void killedPoolFailsClosedBeforeWalletMutation() {
        when(config.activeValue("G.staking.usdt30d.killed")).thenReturn(Optional.of("true"));

        assertThatThrownBy(() -> service.open(
                42L, "open-killed", new AppStakingService.OpenRequest("usdt30d", new BigDecimal("100"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("STAKING_POOL_KILLED");
    }

    private AppStakingMapper.ProductRow product() {
        return new AppStakingMapper.ProductRow(
                2L, "USDT_30D", "USDT 30D", "USDT", 30,
                new BigDecimal("1200"), new BigDecimal("500"), new BigDecimal("20"), "ACTIVE");
    }
}
