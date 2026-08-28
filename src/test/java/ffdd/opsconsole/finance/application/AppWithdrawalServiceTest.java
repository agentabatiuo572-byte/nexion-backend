package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.Attribution;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.PayoutAddressRow;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.WalletRow;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.WithdrawalRiskFacts;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.WithdrawalWrite;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper.WithdrawalAttemptRow;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.facade.WithdrawalRiskDecision;
import ffdd.opsconsole.risk.facade.WithdrawalRiskRuleFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.mockito.ArgumentCaptor;

class AppWithdrawalServiceTest {
    private final ConcurrentHashMap<String, WithdrawalAttemptRow> attempts = new ConcurrentHashMap<>();
    private final AppWithdrawalMapper mapper = mock(AppWithdrawalMapper.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final GrowthRhythmFacade rhythmFacade = mock(GrowthRhythmFacade.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final WithdrawalRiskRuleFacade k3 = mock(WithdrawalRiskRuleFacade.class);
    private final TreasuryLedgerPostingFacade ledger = mock(TreasuryLedgerPostingFacade.class);
    private final MockEnvironment environment = productionEnvironment();
    private final AppWithdrawalService service = new AppWithdrawalService(
            mapper, config, rhythmFacade, idempotency, audit, outbox, k3, ledger, null, environment);

    private static MockEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        attempts.clear();
        environment.setActiveProfiles("prod");
        when(mapper.lockActiveUser(7L)).thenReturn(7L);
        when(mapper.findActiveUser(7L)).thenReturn(7L);
        when(mapper.lockPayoutAddress(7L, "USDT-TRC20")).thenReturn(new PayoutAddressRow(
                "USDT-TRC20", "TR7NHqExampleAddress", LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(6)));
        when(mapper.countLast24Hours(7L)).thenReturn(0);
        when(mapper.withdrawalRiskFacts(7L, "TR7NHqExampleAddress")).thenReturn(
                new WithdrawalRiskFacts("U00000007", 0, BigDecimal.ZERO, 30, "normal",
                        45, "k4-v13", LocalDateTime.now(), 41, 73, 91));
        when(mapper.lockWallet(7L)).thenReturn(new WalletRow(
                7L, new BigDecimal("500.000000"), new BigDecimal("50.000000"), BigDecimal.ZERO, 3L));
        when(config.activeValue("withdrawal.trc20.enabled")).thenReturn(Optional.of("true"));
        when(config.activeValue("withdrawal.bep20.enabled")).thenReturn(Optional.of("true"));
        when(config.activeValue("withdrawal.erc20.enabled")).thenReturn(Optional.of("true"));
        when(config.activeValue("withdrawal.d5.version")).thenReturn(Optional.of("1"));
        when(config.activeValue("withdrawal.network_confirm_fee_usd.trc20")).thenReturn(Optional.of("1"));
        when(config.activeValue("withdrawal.network_confirm_fee_usd.bep20")).thenReturn(Optional.of("1"));
        when(config.activeValue("withdrawal.network_confirm_fee_usd.erc20")).thenReturn(Optional.of("5"));
        when(config.activeValue("withdrawal.daily_count_limit")).thenReturn(Optional.of("2"));
        when(config.activeValue("withdrawal.max_balance_pct")).thenReturn(Optional.of("0.8"));
        when(config.activeValue("withdrawal.nex_fee_offset_rate")).thenReturn(Optional.of("0.4"));
        when(config.activeValue("withdrawal.small_amount_threshold_usd")).thenReturn(Optional.of("50"));
        when(config.activeValue("withdrawal.strong_review_threshold_usdt")).thenReturn(Optional.of("1000"));
        when(config.activeValue("withdrawal.payout_sla_hours")).thenReturn(Optional.of("24"));
        when(config.activeValue("withdrawal.fee_rate")).thenReturn(Optional.of("0.02"));
        when(config.activeValue("withdrawal.fee_min_usdt")).thenReturn(Optional.of("0.50"));
        when(config.activeValue("withdrawal.fee_max_usdt")).thenReturn(Optional.of("20.00"));
        GrowthRhythmSnapshot rhythm = mock(GrowthRhythmSnapshot.class);
        when(rhythm.currentMonth()).thenReturn(3);
        when(rhythm.currentPhase()).thenReturn("P2");
        when(rhythm.withdrawCooldownDays()).thenReturn(30);
        when(rhythm.withdrawPenaltyFeeRate()).thenReturn(new BigDecimal("20"));
        when(rhythmFacade.snapshot()).thenReturn(rhythm);
        when(mapper.reserveFunds(eq(7L), any(), any(), eq(3L))).thenReturn(1);
        when(mapper.insertWithdrawal(any())).thenReturn(1);
        when(mapper.insertWithdrawalAttempt(eq(7L), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(1);
                    String hash = invocation.getArgument(2);
                    String status = invocation.getArgument(3);
                    return attempts.putIfAbsent(key, new WithdrawalAttemptRow(7L, key, hash, status, null)) == null ? 1 : 0;
                });
        when(mapper.lockWithdrawalAttempt(eq(7L), anyString()))
                .thenAnswer(invocation -> attempts.get(invocation.getArgument(1, String.class)));
        when(mapper.commitWithdrawalAttempt(eq(7L), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(1);
                    WithdrawalAttemptRow row = attempts.get(key);
                    if (row == null || "ABANDONED".equals(row.status())) return 0;
                    attempts.put(key, new WithdrawalAttemptRow(7L, key, row.requestHash(), "COMMITTED",
                            invocation.getArgument(2)));
                    return 1;
                });
        when(mapper.abandonWithdrawalAttempt(eq(7L), anyString()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(1);
                    WithdrawalAttemptRow row = attempts.get(key);
                    if (row == null || !"ACTIVE".equals(row.status())) return 0;
                    attempts.put(key, new WithdrawalAttemptRow(7L, key, row.requestHash(), "ABANDONED", null));
                    return 1;
                });
        when(mapper.attribution(7L)).thenReturn(new Attribution("P2", 1, "2026-W30"));
        when(k3.evaluate(any())).thenReturn(new WithdrawalRiskDecision("pass", null, null, java.util.List.of()));
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
    }

    @Test
    void snapshotsFixedD5FeeWithoutImplicitNexBurnOrLegacyH1Penalty() {
        ApiResult<java.util.Map<String, Object>> result = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("penaltyFeeRate", new BigDecimal("0.000000"))
                .containsEntry("networkConfirmUsd", new BigDecimal("1.000000"))
                .containsEntry("networkFee", new BigDecimal("1.000000"))
                .containsEntry("grossFee", new BigDecimal("1.000000"))
                .containsEntry("nexBurned", new BigDecimal("0.000000"))
                .containsEntry("useNexFeeOffset", false)
                .containsEntry("actualFee", new BigDecimal("1.000000"))
                .containsEntry("penaltyFeeWaived", new BigDecimal("0.000000"))
                .containsEntry("networkFeeWaived", new BigDecimal("0.000000"))
                .containsEntry("actualPenaltyFee", new BigDecimal("0.000000"))
                .containsEntry("actualNetworkFee", new BigDecimal("1.000000"))
                .containsEntry("netReceive", new BigDecimal("99.000000"))
                .containsEntry("status", "REVIEW_PENDING");
        verify(mapper).reserveFunds(7L, new BigDecimal("100.000000"), new BigDecimal("0.000000"), 3L);
        verify(ledger).postLedgerEntry(
                anyString(), eq(7L), eq("WITHDRAW_NET_PRINCIPAL"), eq("USDT"), eq("OUT"),
                eq(new BigDecimal("99.000000")), eq("POSTED"), eq("D2 withdrawal net principal"));
        verify(ledger).postLedgerEntry(
                anyString(), eq(7L), eq("WITHDRAW_NETWORK_FEE"), eq("USDT"), eq("OUT"),
                eq(new BigDecimal("1.000000")), eq("POSTED"),
                eq("D5 actual network fee after NEX offset"));
        verify(ledger, never()).postLedgerEntry(
                anyString(), eq(7L), eq("WITHDRAW_FEE_OFFSET"), eq("NEX"), eq("OUT"),
                any(), anyString(), anyString());
        verify(ledger, never()).postLedgerEntry(
                anyString(), eq(7L), eq("WITHDRAW_PENALTY_FEE"), eq("USDT"), eq("OUT"),
                any(), anyString(), anyString());
        ArgumentCaptor<WithdrawalWrite> write = ArgumentCaptor.forClass(WithdrawalWrite.class);
        verify(mapper).insertWithdrawal(write.capture());
        assertThat(write.getValue().freezePeriod()).isEqualTo("H1:M3:P2");
        assertThat(write.getValue().penaltyFeeRate()).isEqualByComparingTo("0");
        assertThat(write.getValue().networkFeeRate()).isEqualByComparingTo("0");
        assertThat(write.getValue().networkFeeMin()).isEqualByComparingTo("1.000000");
        assertThat(write.getValue().networkFeeMax()).isEqualByComparingTo("1.000000");
        assertThat(write.getValue().networkFee()).isEqualByComparingTo("1.000000");
        assertThat(write.getValue().policyVersion()).isNotBlank();
        assertThat(write.getValue().useNexFeeOffset()).isFalse();
        assertThat(write.getValue().idempotencyKey()).isEqualTo("wd-1");
        assertThat(write.getValue().holdUntil()).isAfter(java.time.LocalDateTime.now().plusDays(29));
        verify(outbox).publishUserEvent(eq("WITHDRAWAL"), anyString(), eq("withdraw.submitted"), eq(7L),
                eq("P2"), eq(1), eq("2026-W30"), any());
    }

    @Test
    void amountAtA3StrongReviewThresholdForcesManualReviewEvenWhenK3Passes() {
        when(config.activeValue("withdrawal.strong_review_threshold_usdt")).thenReturn(Optional.of("100"));

        ApiResult<java.util.Map<String, Object>> result = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-strong-review");

        assertThat(result.getData()).containsEntry("status", "REVIEW_PENDING")
                .containsEntry("riskRoute", "strong-review")
                .containsEntry("strongReview", true);
    }

    @Test
    void missingA3StrongReviewThresholdFailsClosedBeforeFundsAreReserved() {
        when(config.activeValue("withdrawal.strong_review_threshold_usdt")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-threshold-missing"))
                .hasMessage("A3_STRONG_REVIEW_THRESHOLD_UNAVAILABLE");
        verify(mapper, never()).reserveFunds(eq(7L), any(), any(), anyLong());
    }

    @Test
    void staleRequestedPolicyVersionFailsBeforeAddressOrFundsMutation() {
        ApiResult<java.util.Map<String, Object>> result = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "stale-policy", false,
                "wd-stale-policy");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_POLICY_VERSION_CONFLICT");
        verify(mapper, never()).lockPayoutAddress(any(), anyString());
        verify(mapper, never()).reserveFunds(any(), any(), any(), any());
    }

    @Test
    void abandonedAmbiguousAttemptBecomesAServerTombstoneAndCannotLaterSubmit() {
        String hash = service.withdrawalAttemptHashForTest(7L, new BigDecimal("25"), "USDT-TRC20",
                "TR7NHqExampleAddress", "d5-v1", false);
        when(mapper.insertWithdrawalAttempt(7L, "withdrawal:unknown-1", hash, "ABANDONED")).thenReturn(1);
        when(mapper.lockWithdrawalAttempt(7L, "withdrawal:unknown-1"))
                .thenReturn(new WithdrawalAttemptRow(7L, "withdrawal:unknown-1", hash, "ABANDONED", null));

        var abandoned = service.abandonAttempt(7L, "withdrawal:unknown-1", new BigDecimal("25"),
                "USDT-TRC20", "TR7NHqExampleAddress", "d5-v1", false);
        var submit = service.submit(7L, new BigDecimal("25"), "USDT-TRC20", "TR7NHqExampleAddress",
                "d5-v1", false, "withdrawal:unknown-1");

        assertThat(abandoned.getData()).containsEntry("state", "ABANDONED");
        assertThat(submit.getCode()).isEqualTo(409);
        assertThat(submit.getMessage()).isEqualTo("WITHDRAWAL_ATTEMPT_ABANDONED");
        verify(idempotency, never()).execute(anyString(), eq("withdrawal:unknown-1"), anyString(),
                eq(ApiResult.class), any());
        verify(mapper, never()).reserveFunds(eq(7L), any(), any(), anyLong());
    }

    @Test
    void abandonReconcilesAuthoritativeWithdrawalWhenOuterAttemptCommitWasLost() {
        String key = "withdrawal:outer-commit-lost";
        String hash = service.withdrawalAttemptHashForTest(7L, new BigDecimal("25"), "USDT-TRC20",
                "TR7NHqExampleAddress", "d5-v1", false);
        when(mapper.insertWithdrawalAttempt(7L, key, hash, "ABANDONED")).thenReturn(0);
        when(mapper.lockWithdrawalAttempt(7L, key))
                .thenReturn(new WithdrawalAttemptRow(7L, key, hash, "ACTIVE", null));
        when(mapper.findWithdrawalNoByIdempotencyKey(7L, key)).thenReturn("WD-REAL");
        when(mapper.userWithdrawal(7L, "WD-REAL"))
                .thenReturn(Map.of("withdrawalNo", "WD-REAL", "status", "REVIEW_PENDING"));
        when(mapper.commitWithdrawalAttempt(7L, key, "WD-REAL")).thenReturn(1);

        var result = service.abandonAttempt(7L, key, new BigDecimal("25"), "USDT-TRC20",
                "TR7NHqExampleAddress", "d5-v1", false);

        assertThat(result.getCode()).isEqualTo(0);
        assertThat(result.getData()).containsEntry("state", "COMMITTED")
                .containsEntry("withdrawalNo", "WD-REAL");
        verify(mapper, never()).abandonWithdrawalAttempt(eq(7L), eq(key));
    }

    @Test
    void abandonRefusesToTreatAuthoritativeReadbackFailureAsAbandoned() {
        String key = "withdrawal:readback-fails";
        String hash = service.withdrawalAttemptHashForTest(7L, new BigDecimal("25"), "USDT-TRC20",
                "TR7NHqExampleAddress", "d5-v1", false);
        when(mapper.insertWithdrawalAttempt(7L, key, hash, "ABANDONED")).thenReturn(0);
        when(mapper.lockWithdrawalAttempt(7L, key))
                .thenReturn(new WithdrawalAttemptRow(7L, key, hash, "ACTIVE", null));
        when(mapper.findWithdrawalNoByIdempotencyKey(7L, key)).thenReturn("WD-REAL");

        assertThatThrownBy(() -> service.abandonAttempt(7L, key, new BigDecimal("25"), "USDT-TRC20",
                "TR7NHqExampleAddress", "d5-v1", false))
                .isInstanceOf(BizException.class)
                .hasMessage("WITHDRAWAL_ATTEMPT_READBACK_UNAVAILABLE");
        verify(mapper, never()).abandonWithdrawalAttempt(eq(7L), eq(key));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void exactIdempotencyReplayDoesNotDuplicateAnyD4LedgerComponent() {
        AtomicReference<ApiResult> stored = new AtomicReference<>();
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> {
                    ApiResult existing = stored.get();
                    if (existing != null) return existing;
                    ApiResult created = (ApiResult) ((Supplier) invocation.getArgument(4)).get();
                    stored.set(created);
                    return created;
                });

        ApiResult<java.util.Map<String, Object>> first = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-replay");
        ApiResult<java.util.Map<String, Object>> replay = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-replay");

        assertThat(replay.getData().get("withdrawalNo")).isEqualTo(first.getData().get("withdrawalNo"));
        verify(ledger, org.mockito.Mockito.times(2)).postLedgerEntry(
                anyString(), eq(7L), anyString(), anyString(), eq("OUT"),
                any(), eq("POSTED"), anyString());
        verify(mapper, org.mockito.Mockito.times(1)).reserveFunds(
                eq(7L), any(), any(), eq(3L));
        verify(mapper, org.mockito.Mockito.times(1)).insertWithdrawal(any());
    }

    @Test
    void exposesOnlyBackendSupportedNetworksAndExactD5H1PreviewPolicy() {
        when(config.activeValue("withdrawal.erc20.enabled")).thenReturn(Optional.of("false"));

        ApiResult<java.util.Map<String, Object>> result = service.policy(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("networkConfirmFeeUsd", java.util.Map.of(
                        "trc20", new BigDecimal("1"),
                        "bep20", new BigDecimal("1"),
                        "erc20", new BigDecimal("5")))
                .containsEntry("nexFeeOffsetRate", new BigDecimal("0.4"))
                .containsEntry("smallAmountThresholdUsd", new BigDecimal("50"))
                .containsEntry("payoutSlaHours", 24)
                .containsEntry("cooldownDays", 30)
                .containsEntry("withdrawalEnabled", true)
                .containsEntry("gateSource", "J1")
                .containsEntry("enabledNetworks", java.util.List.of("USDT-BEP20", "USDT-TRC20"))
                .containsEntry("source", "D5+H1")
                .containsKey("policyVersion")
                .doesNotContainKeys("networkFeeRate", "networkFeeMin", "networkFeeMax", "penaltyFeeRate");
    }

    @Test
    void j1WithdrawGateBlocksTheRealUserEntryBeforeAnyFinancialOrRiskSideEffect() {
        when(mapper.emergencyValue("killswitch.withdraw")).thenReturn("disabled");

        ApiResult<java.util.Map<String, Object>> result = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-j1-kill");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_KILL_SWITCH_DISABLED");
        verify(mapper, never()).lockPayoutAddress(any(), anyString());
        verify(mapper, never()).reserveFunds(any(), any(), any(), any());
        verify(mapper, never()).insertWithdrawal(any());
        verify(k3, never()).evaluate(any());
        verify(ledger, never()).postLedgerEntry(
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
        verify(outbox, never()).publishUserEvent(
                anyString(), anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void strictTestProfileRejectsCanonicalWithdrawalBeforeAnyMapperOrSideEffect() {
        for (String strictProfile : java.util.List.of("test")) {
            environment.setActiveProfiles(strictProfile);
            assertThatThrownBy(() -> service.submit(
                    7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-" + strictProfile))
                    .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(503))
                    .hasMessage("WITHDRAWAL_PRODUCTION_PROFILE_REQUIRED");
        }
        environment.setActiveProfiles("default", "prod");
        assertThatThrownBy(() -> service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-mixed-profile"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(503))
                .hasMessage("WITHDRAWAL_PRODUCTION_PROFILE_REQUIRED");

        verify(mapper, never()).lockActiveUser(anyLong());
        verify(mapper, never()).lockPayoutAddress(anyLong(), anyString());
        verify(mapper, never()).lockWallet(anyLong());
        verify(mapper, never()).reserveFunds(anyLong(), any(), any(), anyLong());
        verify(mapper, never()).insertWithdrawal(any());
        verify(idempotency, never()).execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any());
        verify(audit, never()).recordRequired(any());
        verify(outbox, never()).publishUserEvent(anyString(), anyString(), anyString(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void developmentProfileAllowsCanonicalWithdrawalHistoryForAnActiveDevelopmentAccount() {
        environment.setActiveProfiles("dev");
        when(mapper.isSandboxUser(7L)).thenReturn(1);
        when(mapper.userWithdrawals(7L, 50)).thenReturn(java.util.List.of());

        ApiResult<java.util.Map<String, Object>> result = service.list(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("source", "nx_withdrawal_order")
                .containsEntry("sourceEnvironment", "PRODUCTION");
    }

    @Test
    void developmentProfileRejectsAProductionAccountBeforeFinancialInteraction() {
        environment.setActiveProfiles("dev");
        when(mapper.isSandboxUser(7L)).thenReturn(0);

        assertThatThrownBy(() -> service.list(7L))
                .isInstanceOf(BizException.class)
                .hasMessage("WITHDRAWAL_DEVELOPMENT_USER_REQUIRED");
    }

    @Test
    void sandboxUserIsRejectedInProductionBeforeWalletOrderLedgerAuditOrOutbox() {
        when(mapper.isSandboxUser(7L)).thenReturn(1);

        assertThatThrownBy(() -> service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-sandbox-user"))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class)
                .hasMessage("WITHDRAWAL_SANDBOX_USER_FORBIDDEN");

        verify(mapper, never()).lockActiveUser(anyLong());
        verify(mapper, never()).lockPayoutAddress(anyLong(), anyString());
        verify(mapper, never()).lockWallet(anyLong());
        verify(mapper, never()).reserveFunds(anyLong(), any(), any(), anyLong());
        verify(mapper, never()).insertWithdrawal(any());
        verify(ledger, never()).postLedgerEntry(anyString(), anyLong(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
        verify(audit, never()).recordRequired(any());
        verify(outbox, never()).publishUserEvent(anyString(), anyString(), anyString(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void productionProfileRefusesSandboxUserBeforeAnyFinancialInteraction() {
        environment.setActiveProfiles("prod");
        when(mapper.isSandboxUser(7L)).thenReturn(1);

        assertThatThrownBy(() -> service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-default-sandbox"))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class)
                .hasMessage("WITHDRAWAL_SANDBOX_USER_FORBIDDEN");

        verify(mapper, never()).lockActiveUser(anyLong());
        verify(mapper, never()).reserveFunds(anyLong(), any(), any(), anyLong());
        verify(mapper, never()).insertWithdrawal(any());
        verify(ledger, never()).postLedgerEntry(anyString(), anyLong(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void legacyDefaultProfileIsRejected() {
        environment.setActiveProfiles("default");

        assertThatThrownBy(() -> service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-explicit-default"))
                .isInstanceOf(BizException.class)
                .hasMessage("WITHDRAWAL_PRODUCTION_PROFILE_REQUIRED");
        verify(mapper, never()).reserveFunds(anyLong(), any(), any(), anyLong());
    }

    @Test
    void policyProjectsTheJ1WithdrawGateForAppFailClosedRendering() {
        when(config.activeValue("withdrawal.erc20.enabled")).thenReturn(Optional.of("false"));
        when(mapper.emergencyValue("killswitch.withdraw")).thenReturn("disabled");

        ApiResult<java.util.Map<String, Object>> result = service.policy(7L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("withdrawalEnabled", false)
                .containsEntry("gateSource", "J1");
    }

    @Test
    void rejectsClientAddressThatDoesNotMatchServerPayoutAddressWithoutMutation() {
        ApiResult<java.util.Map<String, Object>> result = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqDifferent", "wd-2");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_PAYOUT_ADDRESS_MISMATCH");
        verify(mapper, never()).reserveFunds(any(), any(), any(), any());
        verify(mapper, never()).insertWithdrawal(any());
    }

    @Test
    void smallAmountNeverBypassesTheNewAddressDelay() {
        when(mapper.lockPayoutAddress(7L, "USDT-TRC20")).thenReturn(new PayoutAddressRow(
                "USDT-TRC20", "TR7NHqExampleAddress", LocalDateTime.now().plusHours(12),
                LocalDateTime.now().plusDays(7)));

        ApiResult<java.util.Map<String, Object>> result = service.submit(
                7L, new BigDecimal("50"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-small-bypass");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_PAYOUT_ADDRESS_CHANGE_PENDING");
        verify(mapper, never()).reserveFunds(any(), any(), any(), any());
    }

    @Test
    void eligibilityNeverAdvertisesSubmitWhileTheServerPayoutAddressIsPending() {
        when(mapper.walletForEligibility(7L)).thenReturn(new WalletRow(
                7L, new BigDecimal("500.000000"), new BigDecimal("50.000000"), BigDecimal.ZERO, 3L));
        when(mapper.payoutAddressForEligibility(7L, "USDT-TRC20")).thenReturn(new PayoutAddressRow(
                "USDT-TRC20", "TR7NHqExampleAddress", LocalDateTime.now().plusHours(12),
                LocalDateTime.now().plusDays(7)));

        ApiResult<java.util.Map<String, Object>> result = service.eligibility(
                7L, new BigDecimal("20"), "USDT-TRC20", "TR7NHqExampleAddress",
                String.valueOf(service.policy(7L).getData().get("policyVersion")));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("canSubmit", false);
        assertThat(result.getData().get("riskReasons")).asList()
                .contains("PAYOUT_ADDRESS_CHANGE_PENDING");
        assertThat(result.getData().get("waivedGates")).asList()
                .doesNotContain("new-address-hold");
    }

    @Test
    void amountAboveSmallThresholdAlsoHonorsTheNewAddressDelay() {
        when(mapper.lockPayoutAddress(7L, "USDT-TRC20")).thenReturn(new PayoutAddressRow(
                "USDT-TRC20", "TR7NHqExampleAddress", LocalDateTime.now().plusHours(12),
                LocalDateTime.now().plusDays(7)));

        ApiResult<java.util.Map<String, Object>> result = service.submit(
                7L, new BigDecimal("50.000001"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-small-boundary");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_PAYOUT_ADDRESS_CHANGE_PENDING");
        verify(mapper, never()).reserveFunds(any(), any(), any(), any());
    }

    @Test
    void enforcesServerDailyLimitBeforeFundsMove() {
        when(mapper.countLast24Hours(7L)).thenReturn(2);

        ApiResult<java.util.Map<String, Object>> result = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-3");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_DAILY_LIMIT_EXCEEDED");
        verify(mapper, never()).reserveFunds(any(), any(), any(), any());
    }

    @Test
    void failsClosedWithoutCurrentK4ScoreBeforeAnyFundsSideEffect() {
        when(mapper.withdrawalRiskFacts(7L, "TR7NHqExampleAddress")).thenReturn(
                new WithdrawalRiskFacts("U00000007", 0, BigDecimal.ZERO, 30, "normal",
                        null, null, null, 41, 73, 91));

        assertThatThrownBy(() -> service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-k4-missing"))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class)
                .hasMessage("K4_RISK_SCORE_UNAVAILABLE");
        verify(mapper, never()).reserveFunds(any(), any(), any(), any());
        verify(mapper, never()).insertWithdrawal(any());
        verify(ledger, never()).postLedgerEntry(
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
        verify(outbox, never()).publishUserEvent(
                anyString(), anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void failsClosedOnStaleK4ScoreBeforeAnyFundsSideEffect() {
        when(mapper.withdrawalRiskFacts(7L, "TR7NHqExampleAddress")).thenReturn(
                new WithdrawalRiskFacts("U00000007", 0, BigDecimal.ZERO, 30, "normal",
                        45, "k4-v13", LocalDateTime.now().minusDays(2), 41, 73, 91));

        assertThatThrownBy(() -> service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-k4-stale"))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class)
                .hasMessage("K4_RISK_SCORE_UNAVAILABLE");
        verify(mapper, never()).reserveFunds(any(), any(), any(), any());
        verify(mapper, never()).insertWithdrawal(any());
        verify(ledger, never()).postLedgerEntry(
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void lowK4WithNoStricterGateWaitsForH1CooldownBeforeCanonicalApproval() {
        when(mapper.withdrawalRiskFacts(7L, "TR7NHqExampleAddress")).thenReturn(
                new WithdrawalRiskFacts("U00000007", 0, BigDecimal.ZERO, 30, "normal",
                        40, "k4-v13", LocalDateTime.now(), 41, 73, 91));

        ApiResult<java.util.Map<String, Object>> result = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-k4-low");

        assertThat(result.getData()).containsEntry("status", "EXTENDED_HOLD")
                .containsEntry("riskRoute", "fast-pass")
                .containsEntry("k4Priority", "LOW");
        ArgumentCaptor<WithdrawalWrite> write = ArgumentCaptor.forClass(WithdrawalWrite.class);
        verify(mapper).insertWithdrawal(write.capture());
        assertThat(write.getValue().status()).isEqualTo("EXTENDED_HOLD");
        assertThat(write.getValue().previousStatus()).isEqualTo("REVIEW_PASSED");
        assertThat(write.getValue().routingPriority()).isEqualTo("LOW");
        assertThat(write.getValue().holdUntil()).isAfter(LocalDateTime.now().plusDays(29));
        verify(outbox, never()).publishUserEvent(eq("WITHDRAWAL"), anyString(), eq("withdraw.approved"), eq(7L),
                anyString(), any(), any(), any());
    }

    @Test
    void escalatedK4UsesManualQueueWithoutFreezingAndNotifiesRiskLead() {
        when(mapper.withdrawalRiskFacts(7L, "TR7NHqExampleAddress")).thenReturn(
                new WithdrawalRiskFacts("U00000007", 0, BigDecimal.ZERO, 30, "normal",
                        91, "k4-v13", LocalDateTime.now(), 41, 73, 91));

        ApiResult<java.util.Map<String, Object>> result = service.submit(
                7L, new BigDecimal("100"), "USDT-TRC20", "TR7NHqExampleAddress", "wd-k4-escalated");

        assertThat(result.getData()).containsEntry("status", "REVIEW_PENDING")
                .containsEntry("riskRoute", "escalated-manual")
                .containsEntry("k4Priority", "ESCALATED");
        ArgumentCaptor<WithdrawalWrite> write = ArgumentCaptor.forClass(WithdrawalWrite.class);
        verify(mapper).insertWithdrawal(write.capture());
        assertThat(write.getValue().failureReason()).startsWith("K4_ESCALATED:91");
        assertThat(write.getValue().routingPriority()).isEqualTo("ESCALATED");
        verify(outbox).publishUserEvent(eq("WITHDRAWAL"), anyString(), eq("risk.withdraw_escalated"), eq(7L),
                eq("P2"), eq(1), eq("2026-W30"), any());
    }
}
