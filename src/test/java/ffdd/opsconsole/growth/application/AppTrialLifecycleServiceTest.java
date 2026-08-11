package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.Attribution;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.PolicyRow;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.TrialRow;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.WalletRow;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppTrialLifecycleServiceTest {
    private final AppTrialLifecycleMapper mapper = mock(AppTrialLifecycleMapper.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final TreasuryCoverageFacade coverage = mock(TreasuryCoverageFacade.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final EarningsReleaseService earningsRelease = mock(EarningsReleaseService.class);
    private final AppTrialLifecycleService service = new AppTrialLifecycleService(
            mapper, earningsRelease, idempotency, coverage, audit, outbox);

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        when(mapper.activeUser(7L)).thenReturn(7L);
        when(mapper.lockActiveUser(7L)).thenReturn(7L);
        when(mapper.policies()).thenReturn(List.of(
                new PolicyRow("phaseOpen", "true"),
                new PolicyRow("trialDays", "3"),
                new PolicyRow("shadowDailyUSD", "40"),
                new PolicyRow("shadowDailyNEX", "5"),
                new PolicyRow("trialOffsetCapUSD", "50"),
                new PolicyRow("trialPriceUSD", "1299")));
        when(mapper.attribution(7L)).thenReturn(new Attribution("P2", 2, "2026-W30"));
        when(mapper.failTrial(anyLong(), anyLong(), any(), any(), any(), any(), anyString())).thenReturn(1);
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("120"), new BigDecimal("85")));
    }

    @Test
    void stateRejectsDeletedOrInactiveAuthenticatedIdentity() {
        when(mapper.activeUser(7L)).thenReturn(null);

        ApiResult<Map<String, Object>> result = service.state(7L);

        assertThat(result.getCode()).isEqualTo(404);
        assertThat(result.getMessage()).isEqualTo("USER_NOT_FOUND");
        verify(mapper, never()).trial(7L);
    }

    @Test
    void trialCycleSignalBlocksBeforeClaimMutation() {
        when(mapper.trialCycleSignalCount(7L)).thenReturn(1L);

        ApiResult<Map<String, Object>> result = service.start(7L, null, "Trial", "h2-cycle");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("TRIAL_CYCLE_RISK_BLOCKED");
        verify(mapper, never()).insertTrial(any(), anyString(), anyString(), any(), anyString(),
                anyInt(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void j1TrialGateBlocksOnlyNewEntryBeforeAnyTrialMutation() {
        when(mapper.emergencyValue("killswitch.trial")).thenReturn("disabled");

        ApiResult<Map<String, Object>> result = service.start(7L, null, "Trial", "h2-j1-kill");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("TRIAL_KILL_SWITCH_DISABLED");
        verify(mapper, never()).trialCycleSignalCount(anyLong());
        verify(mapper, never()).lockTrial(anyLong());
        verify(mapper, never()).insertTrial(any(), anyString(), anyString(), any(), anyString(),
                anyInt(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void stateProjectsJ1TrialGateAndDisablesOnlyEligibility() {
        when(mapper.emergencyValue("killswitch.trial")).thenReturn("disabled");
        when(mapper.trial(7L)).thenReturn(null);

        ApiResult<Map<String, Object>> result = service.state(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("state", "ELIGIBLE")
                .containsEntry("canStart", false)
                .containsEntry("eligibilityReason", "phase-closed")
                .containsEntry("trialGateEnabled", false);
    }

    @Test
    void stateProjectsRiskBlockedEligibilityWithoutTrustingClientStorage() {
        when(mapper.trial(7L)).thenReturn(null);
        when(mapper.trialCycleSignalCount(7L)).thenReturn(1L);

        ApiResult<Map<String, Object>> result = service.state(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("state", "ELIGIBLE")
                .containsEntry("canStart", false)
                .containsEntry("eligibilityReason", "risk")
                .containsEntry("authoritative", true)
                .containsKey("serverNowEpochMs");
    }

    @Test
    void configuredTrialProductMapsToTheServerOwnedTrialDeviceInsteadOfClientInput() {
        when(mapper.policies()).thenReturn(List.of(
                new PolicyRow("phaseOpen", "true"),
                new PolicyRow("trialProductId", "device-trial-standard"),
                new PolicyRow("trialDays", "3"),
                new PolicyRow("shadowDailyUSD", "40"),
                new PolicyRow("shadowDailyNEX", "5"),
                new PolicyRow("trialOffsetCapUSD", "50"),
                new PolicyRow("trialPriceUSD", "1299")));
        when(mapper.insertTrial(anyLong(), anyString(), anyString(), isNull(), anyString(),
                anyInt(), any(), any(), any(), any(), any(), any(), anyString())).thenReturn(1);

        ApiResult<Map<String, Object>> result = service.start(7L, null, "untrusted-client-name", "h2-product-map");

        assertThat(result.getCode()).isZero();
        verify(mapper).insertTrial(eq(7L), anyString(), eq("h2-product-map"), isNull(),
                eq("NexGridBox S1"), anyInt(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void unknownConfiguredTrialProductFailsClosedForReadAndStart() {
        when(mapper.policies()).thenReturn(List.of(
                new PolicyRow("phaseOpen", "true"),
                new PolicyRow("trialProductId", "device-trial-unknown")));
        when(mapper.trial(7L)).thenReturn(null);

        ApiResult<Map<String, Object>> state = service.state(7L);
        ApiResult<Map<String, Object>> start = service.start(7L, null, "untrusted-client-name", "h2-product-invalid");

        assertThat(state.getData()).containsEntry("canStart", false)
                .containsEntry("eligibilityReason", "unknown");
        assertThat(start.getCode()).isEqualTo(409);
        assertThat(start.getMessage()).isEqualTo("TRIAL_PRODUCT_CONFIG_INVALID");
        verify(mapper, never()).insertTrial(any(), anyString(), anyString(), any(), anyString(),
                anyInt(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void stateNormalizesTrialBooleanPolicyEnumsToActualBooleanDtoValues() {
        when(mapper.policies()).thenReturn(List.of(
                new PolicyRow("phaseOpen", "开放"),
                new PolicyRow("autoPushEnabled", "开"),
                new PolicyRow("autoChargeAtEnd", "关")));
        when(mapper.lockTrial(7L)).thenReturn(null);

        ApiResult<Map<String, Object>> result = service.state(7L);

        assertThat(result.getData().get("config"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("phaseOpen", true)
                .containsEntry("autoPushEnabled", true)
                .containsEntry("autoChargeAtEnd", false);
    }

    @Test
    void stateProjectsCorruptPersistedStatusAsUnknownAndNeverEligible() {
        TrialRow corrupt = trialWithStatus(" CORRUPT ", null, 4L);
        when(mapper.lockTrial(7L)).thenReturn(corrupt);

        ApiResult<Map<String, Object>> result = service.state(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("state", "CORRUPT")
                .containsEntry("canStart", false)
                .containsEntry("eligibilityReason", "unknown")
                .containsEntry("version", 4L);
        verify(mapper, never()).restartTrial(any(), anyLong(), anyString(), anyString(), any(), anyString(),
                anyInt(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void startRejectsUnknownPersistedStatusBeforeAnyOverwrite() {
        TrialRow corrupt = trialWithStatus(null, null, 4L);
        when(mapper.lockTrial(7L)).thenReturn(corrupt);

        ApiResult<Map<String, Object>> result = service.start(7L, null, "Trial", "h2-corrupt");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("TRIAL_STATE_UNKNOWN");
        verify(mapper, never()).restartTrial(any(), anyLong(), anyString(), anyString(), any(), anyString(),
                anyInt(), any(), any(), any(), any(), any(), any(), anyString());
        verify(mapper, never()).insertTrial(any(), anyString(), anyString(), any(), anyString(),
                anyInt(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void legacyCancelledTerminalRemainsTheOnlyRestartableFamilyAfterCooldown() {
        TrialRow cancelled = trialWithStatus("CANCELLED", LocalDateTime.now().minusDays(1), 4L);
        TrialRow restarted = trialWithStatus("ACTIVE", null, 5L);
        when(mapper.lockTrial(7L)).thenReturn(cancelled);
        when(mapper.restartTrial(eq(1L), eq(4L), anyString(), eq("h2-legacy-cancelled"),
                any(), anyString(), anyInt(), any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(1);
        when(mapper.trial(7L)).thenReturn(restarted);

        ApiResult<Map<String, Object>> result = service.start(
                7L, null, "Trial", "h2-legacy-cancelled");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("state", "ACTIVE")
                .containsEntry("canStart", false)
                .containsEntry("eligibilityReason", "in-progress");
        verify(mapper).restartTrial(eq(1L), eq(4L), anyString(), eq("h2-legacy-cancelled"),
                any(), anyString(), anyInt(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void redeemedTrialCanNeverBeRestarted() {
        TrialRow redeemed = new TrialRow(1L, 7L, "TRIAL-USED", "REDEEMED", 9L, null, "Trial",
                3, new BigDecimal("40"), new BigDecimal("5"), new BigDecimal("50"),
                new BigDecimal("1299"), LocalDateTime.now().minusDays(3), LocalDateTime.now(),
                new BigDecimal("120"), new BigDecimal("15"), new BigDecimal("70"),
                new BigDecimal("50"), new BigDecimal("1199"), null, 2L);
        when(mapper.lockTrial(7L)).thenReturn(redeemed);

        ApiResult<Map<String, Object>> result = service.start(7L, null, "Trial", "h2-reuse");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("TRIAL_ALREADY_REDEEMED");
        verify(mapper, never()).restartTrial(any(), anyLong(), anyString(), anyString(), any(), anyString(),
                anyInt(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void insufficientWalletMovesToFailedWithCooldownWithoutSettlementMutation() {
        TrialRow active = activeTrial();
        when(mapper.lockTrial(7L)).thenReturn(active);
        when(mapper.lockWallet(7L)).thenReturn(new WalletRow(new BigDecimal("10"), BigDecimal.ZERO));

        ApiResult<Map<String, Object>> result = service.redeemEarly(7L, "h2-low-balance");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("ok", false)
                .containsEntry("reason", "INSUFFICIENT_FUNDS")
                .containsEntry("paymentRail", "NEXION_USDT_WALLET");
        verify(mapper, never()).settleWallet(any(), any(), any(), any());
        verify(mapper).failTrial(eq(1L), eq(0L), any(), any(), any(), any(), eq("INSUFFICIENT_FUNDS"));
        verify(outbox).publishUserEvent(eq("TRIAL"), eq("TRIAL-1"), eq("trial.charge_attempted"),
                eq(7L), eq("P2"), eq(2), eq("2026-W30"), any());
    }

    @Test
    void expiredActiveProjectsAsGraceAndNeverAccruesBeyondConfiguredTrialDays() {
        LocalDateTime now = LocalDateTime.now();
        TrialRow expired = new TrialRow(1L, 7L, "TRIAL-1", "ACTIVE", null, null, "Trial",
                3, new BigDecimal("40"), new BigDecimal("5"), new BigDecimal("50"),
                new BigDecimal("1299"), now.minusDays(8), now.minusDays(5),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, 0L);
        when(mapper.trial(7L)).thenReturn(expired);
        when(mapper.lockTrial(7L)).thenReturn(expired, new TrialRow(1L, 7L, "TRIAL-1", "GRACE", null, null, "Trial",
                3, new BigDecimal("40"), new BigDecimal("5"), new BigDecimal("50"),
                new BigDecimal("1299"), expired.claimedAt(), expired.expiresAt(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, 1L));
        when(mapper.enterGrace(eq(1L), eq(0L), any())).thenReturn(1);

        ApiResult<Map<String, Object>> result = service.state(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("state", "GRACE")
                .containsEntry("canStart", false)
                .containsEntry("shadowUsdt", new BigDecimal("120.000000"))
                .containsEntry("shadowNex", new BigDecimal("15.000000"))
                .containsKey("claimedAtEpochMs")
                .containsKey("expiresAtEpochMs")
                .containsKey("graceEndsAtEpochMs");
        verify(mapper).enterGrace(eq(1L), eq(0L), any());
        verify(outbox).publishUserEvent(eq("TRIAL"), eq("TRIAL-1"), eq("trial.grace_entered"),
                eq(7L), eq("P2"), eq(2), eq("2026-W30"), any());
        assertThat(result.getData().get("config")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .doesNotContainKey("chargeFailRate")
                .containsEntry("trialOffsetCapUSD", "50");
    }

    @Test
    void dueSettlementCancelsServerSideWhenAutoChargeIsDisabled() {
        LocalDateTime now = LocalDateTime.now();
        TrialRow overdue = new TrialRow(1L, 7L, "TRIAL-DUE", "ACTIVE", null, null, "Trial",
                3, new BigDecimal("40"), new BigDecimal("5"), new BigDecimal("50"),
                new BigDecimal("1299"), now.minusDays(12), now.minusDays(9),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, 3L);
        when(mapper.lockTrial(7L)).thenReturn(overdue);
        when(mapper.cancelTrial(eq(1L), eq(3L), eq("auto_end"), any(), any())).thenReturn(1);
        when(mapper.policies()).thenReturn(List.of(
                new PolicyRow("phaseOpen", "true"),
                new PolicyRow("graceDays", "7"),
                new PolicyRow("cooldownDays", "30"),
                new PolicyRow("autoChargeAtEnd", "false")));

        ApiResult<Map<String, Object>> result =
                service.settleDue(7L, "TRIAL-DUE", "h2-auto:TRIAL-DUE");

        assertThat(result.getCode()).isZero();
        verify(mapper).cancelTrial(eq(1L), eq(3L), eq("auto_end"), any(), any());
        verify(mapper, never()).settleWallet(any(), any(), any(), any());
        verify(outbox).publishUserEvent(eq("TRIAL"), eq("TRIAL-DUE"), eq("trial.cancelled"),
                eq(7L), eq("P2"), eq(2), eq("2026-W30"), any());
    }

    private TrialRow activeTrial() {
        LocalDateTime now = LocalDateTime.now();
        return new TrialRow(1L, 7L, "TRIAL-1", "ACTIVE", null, null, "Trial",
                3, new BigDecimal("40"), new BigDecimal("5"), new BigDecimal("50"),
                new BigDecimal("1299"), now.minusDays(1), now.plusDays(2),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, 0L);
    }

    private TrialRow trialWithStatus(String status, LocalDateTime cooldownUntil, long version) {
        LocalDateTime now = LocalDateTime.now();
        boolean active = "ACTIVE".equals(status);
        return new TrialRow(1L, 7L, "TRIAL-LEGACY", status, null, null, "Trial",
                3, new BigDecimal("40"), new BigDecimal("5"), new BigDecimal("50"),
                new BigDecimal("1299"), active ? now.minusDays(1) : now.minusDays(4),
                active ? now.plusDays(2) : now.minusDays(1),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, cooldownUntil, version);
    }
}
