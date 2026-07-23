package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.facade.RiskDisclosureGateFacade;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import ffdd.opsconsole.finance.domain.DepositAggregateView;
import ffdd.opsconsole.finance.domain.DepositBinRiskView;
import ffdd.opsconsole.finance.domain.DepositChargebackView;
import ffdd.opsconsole.finance.domain.DepositFlowView;
import ffdd.opsconsole.finance.domain.DepositOpsRepository;
import ffdd.opsconsole.finance.domain.TopupChargebackRecoveryCommand;
import ffdd.opsconsole.finance.domain.TopupChargebackRecoveryResult;
import ffdd.opsconsole.finance.domain.WithdrawalOrderRepository;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.dto.TopupCommandRequest;
import ffdd.opsconsole.finance.dto.WithdrawalParamUpdateRequest;
import ffdd.opsconsole.finance.dto.WithdrawalLimitsUpdateRequest;
import ffdd.opsconsole.finance.dto.WithdrawalQueryRequest;
import ffdd.opsconsole.finance.dto.WithdrawalReviewRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.risk.domain.RiskRuleView;
import ffdd.opsconsole.risk.facade.KycReviewTriggerResult;
import ffdd.opsconsole.risk.facade.RiskKycReviewFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsFinanceServiceTest {
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final FakeEmergencyControlRepository emergencyRepository = new FakeEmergencyControlRepository();
    private final FakeTreasuryCoverageFacade coverageFacade = new FakeTreasuryCoverageFacade();
    private final FakeWithdrawalOrderRepository withdrawalRepository = new FakeWithdrawalOrderRepository();
    private final FakeDepositOpsRepository depositOpsRepository = new FakeDepositOpsRepository();
    private final FakeRiskKycReviewFacade riskKycReviewFacade = new FakeRiskKycReviewFacade();
    private final RiskOpsRepository riskOpsRepository = mock(RiskOpsRepository.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final ffdd.opsconsole.platform.mapper.AuditObjectLockMapper lockMapper =
            mock(ffdd.opsconsole.platform.mapper.AuditObjectLockMapper.class);
    private final RiskDisclosureGateFacade disclosureGateFacade = mock(RiskDisclosureGateFacade.class);
    private final TreasuryLedgerRepository treasuryLedgerRepository = mock(TreasuryLedgerRepository.class);
    private final EventOutboxService eventOutboxService = mock(EventOutboxService.class);
    private final AdminOperatorRoleResolver operatorRoleResolver = mock(AdminOperatorRoleResolver.class);
    private final OpsFinanceService service =
            new OpsFinanceService(
                    configFacade,
                    emergencyRepository,
                    coverageFacade,
                    withdrawalRepository,
                    depositOpsRepository,
                    riskKycReviewFacade,
                    riskOpsRepository,
                    auditLogService,
                    idempotencyService,
                    ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
                    lockMapper,
                    disclosureGateFacade,
                    treasuryLedgerRepository,
                    eventOutboxService,
                    operatorRoleResolver);

    @BeforeEach
    void setUpRiskDefaults() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        when(riskOpsRepository.withdrawRules()).thenReturn(List.of());
        when(lockMapper.countActiveByTarget(anyString(), anyString(), anyString())).thenReturn(0);
        when(disclosureGateFacade.checkUserGate(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString()))
                .thenReturn(ApiResult.ok(null));
        when(operatorRoleResolver.resolveCode()).thenReturn(null);
        when(idempotencyService.execute(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        emergencyRepository.settings.put("killswitch.withdraw", "enabled");
    }

    private OpsFinanceService service(OpsReadTimeSeedPolicy seedPolicy) {
        return new OpsFinanceService(
                configFacade,
                emergencyRepository,
                coverageFacade,
                withdrawalRepository,
                depositOpsRepository,
                riskKycReviewFacade,
                riskOpsRepository,
                auditLogService,
                idempotencyService,
                seedPolicy,
                lockMapper,
                disclosureGateFacade,
                treasuryLedgerRepository,
                eventOutboxService,
                operatorRoleResolver);
    }

    @Test
    void withdrawalParamsIncludeCoverageAndConfigValues() {
        configFacade.values.put("withdrawal.daily_count_limit", "2");
        configFacade.values.put("withdrawal.max_balance_pct", "0.75");
        configFacade.values.put("withdrawal.nex_fee_offset_rate", "0.55");
        configFacade.values.put("H1.rhythm.totalMonths", "12");
        configFacade.values.put("H1.rhythm.currentMonth", "11");
        configFacade.values.put("H1.rhythm.phaseProgressPct", "92");
        configFacade.values.put("growth.phase.current", "P6");
        configFacade.values.put("growth.phase.month.11.withdrawPenaltyFeeRate", "0.275");
        configFacade.values.put("growth.phase.month.11.withdrawCooldownDays", "14");
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("110.00"), new BigDecimal("85.00"));

        ApiResult<Map<String, Object>> result = service.withdrawalParams();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("dailyLimitCount", 2)
                .containsEntry("maxBalanceRatio", new BigDecimal("0.75"))
                .containsEntry("nexFeeOffsetRate", new BigDecimal("0.55"))
                .containsEntry("coverageRatio", new BigDecimal("110.00"))
                .containsEntry("redlinePct", new BigDecimal("85.00"));
        assertThat(result.getData().get("h1Rhythm"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("currentMonth", 11)
                .containsEntry("currentPhase", "P6");
        assertThat(result.getData().get("h1WithdrawRules"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("sourceDomain", "H1")
                .containsEntry("penaltyFeeRate", new BigDecimal("27.5"))
                .containsEntry("cooldownDays", 14);
    }

    @Test
    void withdrawalParamsSeedsDefaultsIntoConfigWhenMissing() {
        configFacade.values.clear();
        ApiResult<Map<String, Object>> result = service.withdrawalParams();

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).isEmpty();
        assertThat(result.getData())
                .containsEntry("dailyLimitCount", 0)
                .containsEntry("maxBalanceRatio", BigDecimal.ZERO);
    }

    @Test
    void withdrawalParamsCorrectsStaleWalletMirrorsFromCanonicalKeys() {
        configFacade.values.put("withdrawal.min_usdt", "30");
        configFacade.values.put("withdrawal.trc20.enabled", "false");
        configFacade.values.put("withdrawal.erc20.enabled", "true");
        configFacade.values.put("wallet.withdrawal.min_usdt", "10");
        configFacade.values.put("wallet.withdrawal.trc20.enabled", "true");
        configFacade.values.put("wallet.withdrawal.erc20.enabled", "false");

        ApiResult<Map<String, Object>> result = service.withdrawalParams();

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("wallet.withdrawal.min_usdt", "10")
                .containsEntry("wallet.withdrawal.trc20.enabled", "true")
                .containsEntry("wallet.withdrawal.erc20.enabled", "false");
    }

    @Test
    void looseningWithdrawalParamBelowB1CoverageRedlineReturns422() {
        configFacade.values.put("withdrawal.daily_count_limit", "1");
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));
        WithdrawalParamUpdateRequest request =
                new WithdrawalParamUpdateRequest("dailyLimitCount", "2", "increase capacity", "superadmin");

        ApiResult<Map<String, Object>> result = service.updateWithdrawalParam("idem-d5", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
    }

    @Test
    void stricterWithdrawalParamWritesConfigAndAudit() {
        configFacade.values.put("withdrawal.max_balance_pct", "0.80");
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));
        WithdrawalParamUpdateRequest request =
                new WithdrawalParamUpdateRequest("balanceMaxRatio", "70", "tighten risk", "superadmin");

        ApiResult<Map<String, Object>> result = service.updateWithdrawalParam("idem-d5", request);

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("withdrawal.max_balance_pct", "0.7")
                .containsEntry("wallet.withdrawal.max_balance_pct", "0.7");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("D5_WITHDRAWAL_PARAM_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-d5");
    }

    @Test
    void nexFeeOffsetRateIsD5OwnedMirroredAuditedAndPublished() {
        configFacade.values.put("withdrawal.nex_fee_offset_rate", "0.40");
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("120.00"), new BigDecimal("100.00"));

        ApiResult<Map<String, Object>> result = service.updateWithdrawalParam(
                "idem-d5-nex-offset",
                new WithdrawalParamUpdateRequest(
                        "nexFeeOffsetRate", "0.55", "adjust NEX fee offset for current liquidity", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("nexFeeOffsetRate", new BigDecimal("0.55"));
        assertThat(configFacade.values)
                .containsEntry("withdrawal.nex_fee_offset_rate", "0.55")
                .containsEntry("wallet.withdrawal.nex_fee_offset_rate", "0.55");
        verify(eventOutboxService).publish(
                org.mockito.ArgumentMatchers.eq("WITHDRAWAL_PARAM"),
                org.mockito.ArgumentMatchers.eq("withdrawal.nex_fee_offset_rate"),
                org.mockito.ArgumentMatchers.eq("admin.withdraw_limit_changed"),
                org.mockito.ArgumentMatchers.any());
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.any(AuditLogWriteRequest.class));
        verify(idempotencyService).execute(
                org.mockito.ArgumentMatchers.eq("D5_WITHDRAWAL_PARAM_UPDATE"),
                org.mockito.ArgumentMatchers.eq("idem-d5-nex-offset"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(ApiResult.class),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void increasingNexFeeOffsetRateBelowCoverageRedlineIsRejected() {
        configFacade.values.put("withdrawal.nex_fee_offset_rate", "0.40");
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("100.00"));

        ApiResult<Map<String, Object>> result = service.updateWithdrawalParam(
                "idem-d5-nex-offset-redline",
                new WithdrawalParamUpdateRequest(
                        "nexFeeOffsetRate", "0.50", "must not amplify outflow below redline", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(configFacade.values).containsEntry("withdrawal.nex_fee_offset_rate", "0.40");
    }

    @Test
    void canonicalWithdrawalLimitsExposeD5AndH1Sources() {
        seedCanonicalD5();

        ApiResult<Map<String, Object>> result = service.withdrawalLimits();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("version", 7L)
                .containsEntry("dailyLimitCount", 2)
                .containsEntry("balanceMaxRatio", new BigDecimal("0.80"))
                .containsEntry("networkFeeMin", new BigDecimal("0.50"))
                .containsEntry("networkFeeMax", new BigDecimal("20.00"))
                .containsEntry("cooldownDays", 30)
                .containsEntry("penaltyFeeRate", new BigDecimal("0.20"))
                .containsEntry("complianceHoldEnabled", false);
        assertThat(result.getData().get("sourceByField"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("dailyLimitCount", "d5")
                .containsEntry("cooldownDays", "phase-h1");
    }

    @Test
    void canonicalUpdateRejectsAnyPhaseFieldWithRedirect() {
        WithdrawalLimitsUpdateRequest request = new WithdrawalLimitsUpdateRequest();
        request.setCooldownDays(null);

        ApiResult<Map<String, Object>> result = service.updateWithdrawalLimits(null, request);

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("PHASE_PARAM_READONLY");
        assertThat(result.getData()).containsEntry("redirect", "/admin/phase/h1");
    }

    @Test
    void canonicalUpdateUsesVersionCasAndRejectsStaleSnapshot() {
        seedCanonicalD5();
        WithdrawalLimitsUpdateRequest request = canonicalRequest(6L, "tighten stale version test");
        request.setDailyLimitCount(1);

        ApiResult<Map<String, Object>> result = service.updateWithdrawalLimits("d5-cas-stale", request);

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("CONFIG_VERSION_CONFLICT");
        assertThat(configFacade.values).containsEntry("withdrawal.daily_count_limit", "2");
    }

    @Test
    void canonicalTighteningBelowRedlineUpdatesAggregateVersionAndMirrors() {
        seedCanonicalD5();
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80"), new BigDecimal("100"));
        WithdrawalLimitsUpdateRequest request = canonicalRequest(7L, "tighten limits below coverage redline");
        request.setDailyLimitCount(1);
        request.setNetworkFeeRatio(new BigDecimal("0.03"));

        ApiResult<Map<String, Object>> result = service.updateWithdrawalLimits("d5-tighten", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("version", 8L);
        assertThat(configFacade.values)
                .containsEntry("withdrawal.daily_count_limit", "1")
                .containsEntry("wallet.withdrawal.daily_count_limit", "1")
                .containsEntry("withdrawal.fee_rate", "0.03")
                .containsEntry("wallet.withdrawal.fee_rate", "0.03")
                .containsEntry("withdrawal.d5.version", "8");
    }

    @Test
    void canonicalAmplificationBelowRedlineIsRejectedWithoutPartialWrites() {
        seedCanonicalD5();
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80"), new BigDecimal("100"));
        WithdrawalLimitsUpdateRequest request = canonicalRequest(7L, "amplify limits below coverage redline");
        request.setDailyLimitCount(3);
        request.setNetworkFeeMin(new BigDecimal("0.25"));

        ApiResult<Map<String, Object>> result = service.updateWithdrawalLimits("d5-amplify", request);

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("COVERAGE_BELOW_REDLINE");
        assertThat(configFacade.values)
                .containsEntry("withdrawal.daily_count_limit", "2")
                .containsEntry("withdrawal.fee_min_usdt", "0.50")
                .containsEntry("withdrawal.d5.version", "7");
    }

    @Test
    void canonicalNetworkFeeRangeIsAtomic() {
        seedCanonicalD5();
        WithdrawalLimitsUpdateRequest request = canonicalRequest(7L, "invalid network fee range should roll back");
        request.setNetworkFeeMin(new BigDecimal("30"));
        request.setNetworkFeeMax(new BigDecimal("20"));

        ApiResult<Map<String, Object>> result = service.updateWithdrawalLimits("d5-fee-range", request);

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).isEqualTo("NETWORK_FEE_RANGE_INVALID");
        assertThat(configFacade.values).containsEntry("withdrawal.d5.version", "7");
    }

    @Test
    void nexFeeOffsetRateRejectsPositiveInputThatRoundsToZero() {
        configFacade.values.put("withdrawal.nex_fee_offset_rate", "0.40");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.updateWithdrawalParam(
                        "idem-d5-nex-offset-tiny",
                        new WithdrawalParamUpdateRequest(
                                "nexFeeOffsetRate", "0.0000004", "must not persist a rounded zero rate", "superadmin")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");

        assertThat(configFacade.values).containsEntry("withdrawal.nex_fee_offset_rate", "0.40");
    }

    @Test
    void reviewWithdrawalRejectsIllegalTransitionWith409() {
        withdrawalRepository.order = withdrawal("WD-1", "SUCCESS");
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "manual review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-1", "idem-review", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.name());
    }

    @Test
    void reviewWithdrawalCannotUnfreezeAnOrderOwnedByC2AccountFreeze() {
        withdrawalRepository.order = withdrawal("WD-C2", "FROZEN");
        withdrawalRepository.c2FrozenByUserStatus = true;

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal(
                "WD-C2", "idem-c2-unfreeze-bypass",
                new WithdrawalReviewRequest("UNFREEZE", "superadmin", "manual D2 bypass must fail"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_FROZEN_BY_C2_USER_STATUS");
        assertThat(withdrawalRepository.lastStatus).isNull();
    }

    @Test
    void reviewWithdrawalCannotRejectAnOrderOwnedByC2AccountFreezeAndLeaveADirtyMarker() {
        withdrawalRepository.order = withdrawal("WD-C2-REJECT", "FROZEN");
        withdrawalRepository.c2FrozenByUserStatus = true;

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal(
                "WD-C2-REJECT", "idem-c2-reject-bypass",
                new WithdrawalReviewRequest("REJECT", "superadmin", "manual terminal override must fail"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_FROZEN_BY_C2_USER_STATUS");
        assertThat(withdrawalRepository.lastStatus).isNull();
        assertThat(withdrawalRepository.c2FrozenByUserStatus).isTrue();
    }

    @Test
    void reviewWithdrawalApprovesReviewingOrderAndAudits() {
        withdrawalRepository.order = withdrawal("WD-1", "REVIEWING");
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "manual review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-1", "idem-review", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("REVIEW_PASSED");
        assertThat(withdrawalRepository.lastStatus).isEqualTo("REVIEW_PASSED");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("D2_WITHDRAWAL_REVIEW_APPROVE");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("fromStatus", "REVIEWING")
                .containsEntry("toStatus", "REVIEW_PASSED")
                .containsEntry("idempotencyKey", "idem-review");
    }

    @Test
    void reviewWithdrawalFailsClosedWhenK4ScoreIsUnavailable() {
        withdrawalRepository.order = withdrawal(
                "WD-K4-MISSING", "REVIEWING", "VERIFIED", "ACTIVE", null, "", 1);

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal(
                "WD-K4-MISSING", "idem-k4-missing",
                new WithdrawalReviewRequest("APPROVE", "superadmin", "K4 score is required"));

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("K4_RISK_SCORE_UNAVAILABLE");
        assertThat(withdrawalRepository.lastStatus).isNull();
        verify(treasuryLedgerRepository, org.mockito.Mockito.never()).recordWithdrawalReserve(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void missingJ1RowsKeepTheRealD2WithdrawalPathEnabledLikeTheJ1DisplayDefault() {
        emergencyRepository.settings.remove("killswitch.withdraw");
        emergencyRepository.settings.remove("emergency.killswitch.withdraw");
        withdrawalRepository.order = withdrawal("WD-DEFAULT-ON", "REVIEWING");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal(
                "WD-DEFAULT-ON",
                "idem-withdraw-default-on",
                new WithdrawalReviewRequest("APPROVE", "superadmin", "verify shared default state"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("REVIEW_PASSED");
    }

    @Test
    void reviewWithdrawalCannotApproveWhenUserDisclosureAckIsStale() {
        withdrawalRepository.order = withdrawal("WD-I5-1", "REVIEWING");
        when(disclosureGateFacade.checkUserGate(1001L, "withdraw", "WD-I5-1"))
                .thenReturn(ApiResult.fail(409, "RISK_DISCLOSURE_ACK_REQUIRED"));

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal(
                "WD-I5-1", "idem-i5-gate", new WithdrawalReviewRequest("APPROVE", "superadmin", "manual review"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("RISK_DISCLOSURE_ACK_REQUIRED");
        assertThat(withdrawalRepository.lastStatus).isNull();
        verify(disclosureGateFacade).checkUserGate(1001L, "withdraw", "WD-I5-1");
    }

    @Test
    void reviewWithdrawalTriggersK5AndFreezesLargeWithdrawalBeforeApprove() {
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                "authenticated-finance-admin", "n/a", java.util.List.of());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        withdrawalRepository.order = withdrawal("WD-LARGE-1", "REVIEWING", new BigDecimal("8200.00"));
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "forged-admin", "large withdrawal review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-LARGE-1", "idem-review", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_K5_REVIEW_REQUIRED");
        assertThat(withdrawalRepository.lastStatus).isEqualTo("FROZEN");
        assertThat(withdrawalRepository.lastFailureReason).isEqualTo("K5_REVIEW:KR-D2-TEST");
        assertThat(riskKycReviewFacade.lastWithdrawalNo).isEqualTo("WD-LARGE-1");
        assertThat(riskKycReviewFacade.lastAmountUsdt).isEqualByComparingTo("8200.00");
        assertThat(riskKycReviewFacade.lastOperator).isEqualTo("admin:authenticated-finance-admin");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("D2_WITHDRAWAL_K5_REVIEW_REQUIRED");
        assertThat(captor.getValue().getActorUsername()).isEqualTo("admin:authenticated-finance-admin");
        assertThat(captor.getValue().getResult()).isEqualTo("BLOCKED");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("toStatus", "FROZEN")
                .containsEntry("blockedReason", "WITHDRAWAL_K5_REVIEW_REQUIRED")
                .containsEntry("k5TicketId", "KR-D2-TEST")
                .containsEntry("k5Created", true);
    }

    @Test
    void concurrentD2StateChangePreventsK5HoldFromOverwritingTheWinner() {
        withdrawalRepository.order = withdrawal("WD-K5-RACE", "REVIEWING", new BigDecimal("8200.00"));
        withdrawalRepository.failK5Freeze = true;

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.reviewWithdrawal(
                "WD-K5-RACE", "idem-k5-freeze-race",
                new WithdrawalReviewRequest("APPROVE", "superadmin", "concurrent terminal transition wins")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("D2_K5_HOLD_CONCURRENT_UPDATE");
        assertThat(withdrawalRepository.lastStatus).isNull();
        verify(auditLogService, org.mockito.Mockito.never()).record(
                org.mockito.ArgumentMatchers.argThat(audit ->
                        "D2_WITHDRAWAL_K5_REVIEW_REQUIRED".equals(audit.getAction())));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"APPROVE", "UNFREEZE", "REJECT"})
    void ordinaryD2ReviewCannotBypassK5TicketHold(String action) {
        WithdrawalOrderView held = withdrawal("WD-K5-HELD", "FROZEN");
        withdrawalRepository.order = new WithdrawalOrderView(
                held.id(), held.userId(), held.withdrawalNo(), held.asset(), held.chain(), held.amount(), held.fee(),
                held.targetAddress(), held.riskDecisionId(), held.chainTxHash(), held.status(), held.chainSubmittedAt(),
                held.completedAt(), held.failedAt(), "K5_REVIEW:KR-D2-HELD", held.chainBroadcastAttempts(),
                held.nextBroadcastAt(), held.lastBroadcastError(), held.broadcastDeadAt(), held.createdAt(), held.updatedAt(),
                held.userNo(), held.nickname(), held.phoneMasked(), held.kycStatus(), held.userStatus(), held.riskScore(),
                held.hitRules(), held.riskReason(), held.withdrawalCount24h(), held.statusHistory(), held.auditTrail());

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal(
                "WD-K5-HELD", "idem-k5-held-" + action,
                new WithdrawalReviewRequest(action, "superadmin", "ordinary D2 review must not bypass K5"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_K5_REVIEW_REQUIRED");
        assertThat(withdrawalRepository.lastStatus).isNull();
    }

    @Test
    void reviewWithdrawalDoesNotReevaluateK3WithALocalRuleInterpreter() {
        withdrawalRepository.order = withdrawal("WD-K3-1", "REVIEWING", new BigDecimal("150.00"));
        RiskRuleView rule = new RiskRuleView(
                "WR-K3-1",
                "amount",
                "single >= 100",
                "freeze",
                "active",
                false,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now());
        when(riskOpsRepository.withdrawRules()).thenReturn(List.of(rule));
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "k3 active rule review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-K3-1", "idem-k3-review", request);

        assertThat(result.getCode()).isZero();
        assertThat(withdrawalRepository.lastStatus).isEqualTo("REVIEW_PASSED");
        verify(riskOpsRepository, org.mockito.Mockito.never()).withdrawRules();
    }

    @Test
    void reviewWithdrawalDoesNotDuplicateK3ComparisonParsing() {
        withdrawalRepository.order = withdrawal("WD-K3-LOW-1", "REVIEWING", new BigDecimal("80.00"));
        RiskRuleView rule = new RiskRuleView(
                "WR-K3-LOW-1",
                "amount",
                "single <= 100",
                "freeze",
                "active",
                false,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now());
        when(riskOpsRepository.withdrawRules()).thenReturn(List.of(rule));
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "k3 low amount rule review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-K3-LOW-1", "idem-k3-low-review", request);

        assertThat(result.getCode()).isZero();
        verify(riskOpsRepository, org.mockito.Mockito.never()).withdrawRules();
    }

    @Test
    void reviewWithdrawalUsesCanonicalK3SnapshotInsteadOfIteratingCurrentRules() {
        withdrawalRepository.order = withdrawal("WD-K3-MULTI-1", "REVIEWING", new BigDecimal("150.00"));
        RiskRuleView auditOnlyRule = new RiskRuleView(
                "WR-K3-AUDIT-1",
                "amount",
                "single >= 100",
                "observe",
                "active",
                false,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now());
        RiskRuleView blockingRule = new RiskRuleView(
                "WR-K3-BLOCK-1",
                "amount",
                "single >= 120",
                "freeze",
                "active",
                false,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now());
        when(riskOpsRepository.withdrawRules()).thenReturn(List.of(auditOnlyRule, blockingRule));
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "k3 multiple rules review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-K3-MULTI-1", "idem-k3-multi-review", request);

        assertThat(result.getCode()).isZero();
        assertThat(withdrawalRepository.lastStatus).isEqualTo("REVIEW_PASSED");
        verify(riskOpsRepository, org.mockito.Mockito.never()).withdrawRules();
    }

    @Test
    void reviewWithdrawalRejectsApproveWhenD5DailyLimitExceeded() {
        configFacade.values.put("withdrawal.daily_count_limit", "1");
        withdrawalRepository.order = withdrawal("WD-1", "REVIEWING", 2);
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "manual review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-1", "idem-review", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_DAILY_LIMIT_EXCEEDED");
        assertThat(withdrawalRepository.lastStatus).isNull();

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("D2_WITHDRAWAL_REVIEW_BLOCKED");
        assertThat(captor.getValue().getResult()).isEqualTo("BLOCKED");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("fromStatus", "REVIEWING")
                .containsEntry("toStatus", "REVIEWING")
                .containsEntry("requestedAction", "APPROVE")
                .containsEntry("blockedReason", "WITHDRAWAL_DAILY_LIMIT_EXCEEDED")
                .containsEntry("statusUnchanged", true)
                .containsEntry("withdrawalCount24h", 2)
                .containsEntry("dailyLimitCount", 1)
                .containsEntry("idempotencyKey", "idem-review");
    }

    @Test
    void reviewWithdrawalRejectsApproveWhenB1CoverageBelowRedline() {
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));
        withdrawalRepository.order = withdrawal("WD-1", "REVIEWING");
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "manual review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-1", "idem-review", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        assertThat(withdrawalRepository.lastStatus).isNull();

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("D2_WITHDRAWAL_REVIEW_BLOCKED");
        assertThat(captor.getValue().getResult()).isEqualTo("BLOCKED");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("blockedReason", OpsErrorCode.COVERAGE_BELOW_REDLINE.name())
                .containsEntry("coverageRatio", new BigDecimal("80.00"))
                .containsEntry("redlinePct", new BigDecimal("85.00"))
                .containsEntry("statusUnchanged", true);
    }

    @Test
    void reviewWithdrawalRejectsApproveWhenWithdrawKillSwitchDisabled() {
        emergencyRepository.settings.put("killswitch.withdraw", "disabled");
        withdrawalRepository.order = withdrawal("WD-1", "REVIEWING");
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "manual review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-1", "idem-review", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_KILL_SWITCH_DISABLED");
        assertThat(withdrawalRepository.lastStatus).isNull();

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("D2_WITHDRAWAL_REVIEW_BLOCKED");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("blockedReason", "WITHDRAWAL_KILL_SWITCH_DISABLED")
                .containsEntry("statusUnchanged", true);
    }

    @Test
    void reviewWithdrawalRejectsApproveWhenI4DisclosureGateActive() {
        emergencyRepository.settings.put("disclosure.gate.withdraw", "true");
        withdrawalRepository.order = withdrawal("WD-1", "REVIEWING");
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "manual review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-1", "idem-review", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_DISCLOSURE_REACK_REQUIRED");
        assertThat(withdrawalRepository.lastStatus).isNull();

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("D2_WITHDRAWAL_REVIEW_BLOCKED");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("blockedReason", "WITHDRAWAL_DISCLOSURE_REACK_REQUIRED")
                .containsEntry("statusUnchanged", true);
    }

    @Test
    void reviewWithdrawalRejectsApproveWhenJ2EmergencyGeoBlockActive() {
        emergencyRepository.settings.put("emergency.geo.j4.block.required", "true");
        withdrawalRepository.order = withdrawal("WD-1", "REVIEWING");
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "manual review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-1", "idem-review", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_GEO_BLOCKED");
        assertThat(withdrawalRepository.lastStatus).isNull();

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("D2_WITHDRAWAL_REVIEW_BLOCKED");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("blockedReason", "WITHDRAWAL_GEO_BLOCKED")
                .containsEntry("statusUnchanged", true);
    }

    @Test
    void reviewWithdrawalRejectsApproveWhenJ2WithdrawEndpointPolicyBlocksUserCountry() {
        emergencyRepository.geoEndpointPolicies.add(Map.of(
                "endpointKey", "withdraw",
                "countryCode", "US",
                "source", "explicit"));
        withdrawalRepository.order = withdrawal("WD-1", "REVIEWING");
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "manual review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-1", "idem-review", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_GEO_BLOCKED");
        assertThat(withdrawalRepository.lastStatus).isNull();
    }

    @Test
    void reviewWithdrawalIgnoresRetiredWithdrawGeoEndpointConfigKey() {
        emergencyRepository.settings.put("emergency.geo.endpoint.withdraw.countries", "US");
        withdrawalRepository.order = withdrawal("WD-1", "REVIEWING");
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "manual review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-1", "idem-review", request);

        assertThat(result.getCode()).isEqualTo(0);
        assertThat(withdrawalRepository.lastStatus).isEqualTo("REVIEW_PASSED");
    }

    @Test
    void reviewWithdrawalAllowsDelayWhenD5DailyLimitExceededAndAudits() {
        assertOverLimitReviewActionAudits("DELAY", "EXTENDED_HOLD");
    }

    @Test
    void reviewWithdrawalAllowsFreezeWhenD5DailyLimitExceededAndAudits() {
        assertOverLimitReviewActionAudits("FREEZE", "FROZEN");
    }

    @Test
    void reviewWithdrawalAllowsRejectWhenD5DailyLimitExceededAndAudits() {
        assertOverLimitReviewActionAudits("REJECT", "REFUNDED");
    }

    @Test
    void reviewWithdrawalRejectsApproveWhenKycIsNotApproved() {
        withdrawalRepository.order = withdrawal("WD-1", "REVIEWING", "PENDING", "ACTIVE", 42, "", 1);
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "manual review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-1", "idem-review", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_KYC_NOT_APPROVED");
        assertThat(withdrawalRepository.lastStatus).isNull();
    }

    @Test
    void reviewWithdrawalRejectsApproveWhenUserStatusIsNotActive() {
        withdrawalRepository.order = withdrawal("WD-1", "REVIEWING", "VERIFIED", "FROZEN", 42, "", 1);
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "manual review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-1", "idem-review", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_USER_STATUS_BLOCKED");
        assertThat(withdrawalRepository.lastStatus).isNull();
    }

    @Test
    void reviewWithdrawalAllowsConfirmedManualRouteDespiteHistoricalHitText() {
        withdrawalRepository.order = withdrawal("WD-1", "REVIEWING", "VERIFIED", "ACTIVE", 69, "velocity:FREEZE", 1);
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "manual review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-1", "idem-review", request);

        assertThat(result.getCode()).isZero();
        assertThat(withdrawalRepository.lastStatus).isEqualTo("REVIEW_PASSED");
    }

    @Test
    void reviewWithdrawalAllowsManualRouteAfterHumanReview() {
        withdrawalRepository.order = withdrawal(
                "WD-1",
                "REVIEWING",
                "VERIFIED",
                "ACTIVE",
                42,
                "",
                "单笔大额提现转人工",
                1);
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "manual review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-1", "idem-review", request);

        assertThat(result.getCode()).isZero();
        assertThat(withdrawalRepository.lastStatus).isEqualTo("REVIEW_PASSED");
    }

    @Test
    void reviewWithdrawalAllowsApproveAfterD5DailyLimitRaised() {
        configFacade.values.put("withdrawal.daily_count_limit", "2");
        withdrawalRepository.order = withdrawal("WD-1", "REVIEWING", 2);
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "manual review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-1", "idem-review", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("REVIEW_PASSED");
        assertThat(withdrawalRepository.lastStatus).isEqualTo("REVIEW_PASSED");
    }

    @Test
    void topupOverviewAggregatesDepositsAndConfigState() {
        depositOpsRepository.aggregates = List.of(new DepositAggregateView(
                "USDT-TRC20",
                3L,
                new BigDecimal("150.00"),
                2L,
                new BigDecimal("120.00")));
        configFacade.values.put("finance.topup.channel.trc20.enabled", "false");
        depositOpsRepository.binRows = List.of(new DepositBinRiskView(
                "bin-4716", "BIN · 自动锁", 8L, true, "2026-07-21 00:00 到期", false));

        ApiResult<Map<String, Object>> result = service.topupOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("ledgerCount", 2L)
                .containsEntry("diffCount", 1);
        assertThat(result.getData().get("channels").toString()).contains("USDT-TRC20").contains("false");
        assertThat(result.getData().get("bins").toString()).contains("bin-4716");
    }

    @Test
    void topupOverviewDoesNotCreateFallbackBusinessRowsWhenDatabaseIsEmpty() {
        ApiResult<Map<String, Object>> result = service.topupOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("ledgerCount", 0L);
        assertThat(result.getData().get("reconciliation")).asList().isEmpty();
    }

    @Test
    void topupOverviewMergesProviderAndLedgerAliasesIntoOneCanonicalReconciliationRow() {
        depositOpsRepository.aggregates = List.of(
                new DepositAggregateView("trc20", 3L, new BigDecimal("150.00"), 0L, BigDecimal.ZERO),
                new DepositAggregateView("USDT-TRC20", 0L, BigDecimal.ZERO, 2L, new BigDecimal("120.00")));

        ApiResult<Map<String, Object>> result = service.topupOverview();

        assertThat(result.getCode()).isZero();
        @SuppressWarnings("unchecked")
        List<ffdd.opsconsole.finance.domain.DepositReconciliationRowView> rows =
                (List<ffdd.opsconsole.finance.domain.DepositReconciliationRowView>) result.getData().get("reconciliation");
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.channel()).isEqualTo("USDT-TRC20");
            assertThat(row.providerCount()).isEqualTo(3L);
            assertThat(row.providerAmount()).isEqualByComparingTo("150.00");
            assertThat(row.ledgerCount()).isEqualTo(2L);
            assertThat(row.ledgerAmount()).isEqualByComparingTo("120.00");
            assertThat(row.diffAmount()).isEqualByComparingTo("30.00");
        });
    }

    @Test
    void disabledReadTimeSeedsDoNotSeedTopupFallbackData() {
        OpsFinanceService realOnlyService = service(OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        ApiResult<Map<String, Object>> result = realOnlyService.topupOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("ledgerCount", 0L);
        assertThat(result.getData().get("reconciliation")).asList().isEmpty();
    }

    @Test
    void topupChannelWriteRequiresIdempotencyAndReason() {
        TopupCommandRequest request = new TopupCommandRequest(null, false, "pause incident channel", "superadmin");

        ApiResult<Map<String, Object>> missingIdem = service.updateTopupChannelEnabled("trc20", null, request);
        ApiResult<Map<String, Object>> missingReason = service.updateTopupChannelEnabled(
                "trc20",
                "idem-d1",
                new TopupCommandRequest(null, false, "", "superadmin"));

        assertThat(missingIdem.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
        assertThat(missingReason.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
    }

    @Test
    void d1MissingBusinessValuesReturnValidationInsteadOfThrowingServerErrors() {
        TopupCommandRequest validReasonOnly = new TopupCommandRequest(null, null, "approved operational request", "superadmin");

        assertThat(service.switchTopupPsp("idem-empty-psp", validReasonOnly).getMessage()).isEqualTo("PSP_REQUIRED");
        assertThat(service.createTopupBinLock("idem-empty-bin", validReasonOnly).getMessage()).isEqualTo("BIN_SEGMENT_REQUIRED");
        assertThat(service.refundTopupChargeback(
                null,
                "idem-empty-chargeback",
                new TopupCommandRequest(null, null, null, null, null, "PROOF-1", true,
                        "approved operational request", "superadmin")).getMessage())
                .isEqualTo("CHARGEBACK_CASE_REQUIRED");
    }

    @Test
    void topupChannelWriteRejectsReasonShorterThanEightCharacters() {
        ApiResult<Map<String, Object>> result = service.updateTopupChannelEnabled(
                "trc20",
                "idem-d1-short-reason",
                new TopupCommandRequest(null, false, "short", "forged-client-actor"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
        assertThat(configFacade.values).doesNotContainKey("finance.topup.channel.trc20.enabled");
    }

    @Test
    void topupAllStatusDoesNotSilentlyFilterToConfirmedRows() {
        service.topupFlows("", null, null, 1, 20);

        assertThat(depositOpsRepository.lastFlowStatuses).isEmpty();
    }

    @Test
    void topupOverviewUsesDocumentedDefaultsWhenConfigIsAbsentOrMalformed() {
        configFacade.values.put("finance.topup.card.cardRetryLimit", "not-a-number");

        ApiResult<Map<String, Object>> result = service.topupOverview();

        assertThat(result.getData()).containsEntry("primaryPsp", "Checkout.com");
        assertThat(result.getData().get("channels").toString())
                .contains("1 USDT 固定手续费")
                .contains("$10");
        assertThat(result.getData().get("cardParams").toString())
                .contains("同卡 24 小时失败次数上限")
                .contains("5");
    }

    @Test
    void topupNumericConfigurationRejectsFreeTextAndOutOfRangeValues() {
        ApiResult<Map<String, Object>> freeTextFee = service.updateTopupChannelFee(
                "card", "idem-d1-fee-text",
                new TopupCommandRequest("cheap", null, "valid operational reason", "superadmin"));
        ApiResult<Map<String, Object>> invalidRetryLimit = service.updateTopupCardRiskParam(
                "cardRetryLimit", "idem-d1-retry-range",
                new TopupCommandRequest("999", null, "valid operational reason", "superadmin"));

        assertThat(freeTextFee.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(invalidRetryLimit.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(configFacade.values).doesNotContainKeys(
                "finance.topup.channel.card.fee",
                "finance.topup.card.cardRetryLimit");
    }

    @Test
    void topupStructuredNumericConfigurationPersistsCanonicalValueAndUnit() {
        TopupCommandRequest request = new TopupCommandRequest(
                null, null, new BigDecimal("3.25"), "PERCENT", null, null, null,
                "3.5", "approved card fee update", "forged-client-actor");

        ApiResult<Map<String, Object>> result = service.updateTopupChannelFee("card", "idem-d1-fee-structured", request);

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("finance.topup.channel.card.fee", "3.25")
                .containsEntry("finance.topup.channel.card.fee_unit", "PERCENT");
        verify(idempotencyService).execute(
                org.mockito.ArgumentMatchers.eq("D1_TOPUP_CHANNEL_FEE_CARD"),
                org.mockito.ArgumentMatchers.eq("idem-d1-fee-structured"),
                anyString(),
                org.mockito.ArgumentMatchers.eq(ApiResult.class),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reconciliationWriteoffRequiresMethodEvidenceAndRealDifference() {
        depositOpsRepository.aggregates = List.of(new DepositAggregateView(
                "USDT-TRC20", 2L, new BigDecimal("100"), 1L, new BigDecimal("60")));
        TopupCommandRequest missingEvidence = new TopupCommandRequest(
                null, null, null, null, "CONFIRM_EXCEPTION", null, null,
                "approved mismatch handling", "superadmin");

        ApiResult<Map<String, Object>> rejected = service.writeoffTopupReconciliation(
                "trc20", "idem-d1-recon-missing", missingEvidence);
        ApiResult<Map<String, Object>> accepted = service.writeoffTopupReconciliation(
                "trc20", "idem-d1-recon-ok",
                new TopupCommandRequest(
                        null, null, null, null, "CONFIRM_EXCEPTION", "PSP-STMT-20260720-01", null,
                        "approved mismatch handling", "superadmin"));

        assertThat(rejected.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(accepted.getCode()).isZero();
        assertThat(depositOpsRepository.lastWriteoffMethod).isEqualTo("CONFIRM_EXCEPTION");
        assertThat(depositOpsRepository.lastWriteoffEvidenceRef).isEqualTo("PSP-STMT-20260720-01");
    }

    @Test
    void chargebackRecoveryRequiresEvidenceAndDelegatesAtomicLedgerClosure() {
        depositOpsRepository.chargeback = new DepositChargebackView(
                "CB-100", 42L, "usr_42", new BigDecimal("100.00"), new BigDecimal("3.50"), "fraud", "已入账",
                "CHARGEBACK", LocalDateTime.now().minusHours(1), LocalDateTime.now());
        depositOpsRepository.recoveryResult = new TopupChargebackRecoveryResult(
                new BigDecimal("80.00"), new BigDecimal("20.00"), new BigDecimal("3.50"),
                new BigDecimal("0.00"), "PARTIAL_ANOMALY", "D1-CB-CB-100", "RSK-CB-100");

        ApiResult<Map<String, Object>> rejected = service.refundTopupChargeback(
                "CB-100", "idem-d1-cb-no-proof",
                new TopupCommandRequest(null, null, null, null, null, null, false,
                        "approved recovery request", "superadmin"));
        ApiResult<Map<String, Object>> accepted = service.refundTopupChargeback(
                "CB-100", "idem-d1-cb-ok",
                new TopupCommandRequest(null, null, null, null, null, "DISPUTE-PROOF-100", true,
                        "approved recovery request", "superadmin"));

        assertThat(rejected.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(accepted.getCode()).isZero();
        assertThat(depositOpsRepository.lastRecoveryCommand.caseNo()).isEqualTo("CB-100");
        assertThat(depositOpsRepository.lastRecoveryCommand.feeBufferRequired()).isEqualByComparingTo("3.500000");
        assertThat(depositOpsRepository.lastRecoveryCommand.evidenceRef()).isEqualTo("DISPUTE-PROOF-100");
    }

    @Test
    void legacyStatusOnlyChargebackRemainsRecoverableThroughTheAtomicClosure() {
        depositOpsRepository.chargeback = new DepositChargebackView(
                "CB-LEGACY", 42L, "usr_42", new BigDecimal("100.00"), new BigDecimal("3.50"),
                "legacy status-only refund", "已入账", "CHARGEBACK_REFUNDED",
                LocalDateTime.now().minusDays(1), LocalDateTime.now());
        depositOpsRepository.recoveryResult = new TopupChargebackRecoveryResult(
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("3.50"),
                BigDecimal.ZERO, "RECOVERED", "D1-CB-CB-LEGACY", null);

        ApiResult<Map<String, Object>> result = service.refundTopupChargeback(
                "CB-LEGACY", "idem-d1-cb-legacy",
                new TopupCommandRequest(null, null, null, null, null, "LEGACY-DISPUTE-PROOF", true,
                        "complete legacy atomic recovery", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(depositOpsRepository.lastRecoveryCommand.caseNo()).isEqualTo("CB-LEGACY");
    }

    @Test
    void chargebackRecoveryRejectsCaseWithoutOriginalD4CreditEntry() {
        depositOpsRepository.chargeback = new DepositChargebackView(
                "CB-NO-LEDGER", 42L, "usr_42", new BigDecimal("100.00"), new BigDecimal("3.50"), "fraud", "未找到入账分录",
                "CHARGEBACK", LocalDateTime.now().minusHours(1), LocalDateTime.now());

        ApiResult<Map<String, Object>> result = service.refundTopupChargeback(
                "CB-NO-LEDGER", "idem-d1-cb-no-ledger",
                new TopupCommandRequest(null, null, null, null, null, "DISPUTE-PROOF-100", true,
                        "approved recovery request", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("CHARGEBACK_LEDGER_ENTRY_NOT_FOUND");
        assertThat(depositOpsRepository.lastRecoveryCommand).isNull();
    }

    @Test
    void topupChannelWritePersistsConfigAndAudits() {
        TopupCommandRequest request = new TopupCommandRequest(
                null, false, null, null, null, null, null, "true", "pause incident channel", "superadmin");

        ApiResult<Map<String, Object>> result = service.updateTopupChannelEnabled("trc20", "idem-d1", request);

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("finance.topup.channel.trc20.enabled", "false");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("D1_TOPUP_CHANNEL_STATUS_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-d1");
    }

    @Test
    void withdrawalsPassesServerSideAmountAndRiskFiltersToRepository() {
        withdrawalRepository.order = withdrawal("WD-1", "REVIEWING");

        ApiResult<PageResult<WithdrawalOrderView>> result = service.withdrawals(
                new WithdrawalQueryRequest("REVIEWING", 1001L, "WD", 2, 50, new BigDecimal("1000"), new BigDecimal("5000"), 70));

        assertThat(result.getCode()).isZero();
        assertThat(withdrawalRepository.lastStatusFilter).isEqualTo("REVIEW_PENDING");
        assertThat(withdrawalRepository.lastUserIdFilter).isEqualTo(1001L);
        assertThat(withdrawalRepository.lastKeywordFilter).isEqualTo("WD");
        assertThat(withdrawalRepository.lastMinAmountFilter).isEqualByComparingTo("1000");
        assertThat(withdrawalRepository.lastMaxAmountFilter).isEqualByComparingTo("5000");
        assertThat(withdrawalRepository.lastMinRiskScoreFilter).isEqualTo(70);
    }

    @Test
    void withdrawalsNormalizesReversedAmountRangeBeforeRepositoryQuery() {
        withdrawalRepository.order = withdrawal("WD-1", "REVIEWING");

        ApiResult<PageResult<WithdrawalOrderView>> result = service.withdrawals(
                new WithdrawalQueryRequest("REVIEWING", null, null, 1, 20, new BigDecimal("5000"), new BigDecimal("1000"), null));

        assertThat(result.getCode()).isZero();
        assertThat(withdrawalRepository.lastMinAmountFilter).isEqualByComparingTo("1000");
        assertThat(withdrawalRepository.lastMaxAmountFilter).isEqualByComparingTo("5000");
    }

    @Test
    void withdrawalsDoesNotCreateFallbackBusinessRowsWhenDatabaseIsEmpty() {
        ApiResult<PageResult<WithdrawalOrderView>> result = service.withdrawals(
                new WithdrawalQueryRequest(null, null, null, 1, 20, null, null, null));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getTotal()).isZero();
        assertThat(result.getData().getRecords()).isEmpty();
    }

    @Test
    void disabledReadTimeSeedsDoNotSeedWithdrawalFallbackData() {
        OpsFinanceService realOnlyService = service(OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        ApiResult<PageResult<WithdrawalOrderView>> result = realOnlyService.withdrawals(
                new WithdrawalQueryRequest(null, null, null, 1, 20, null, null, null));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getTotal()).isZero();
        assertThat(result.getData().getRecords()).isEmpty();
    }

    @Test
    void withdrawalsKeepsExistingFinalRowsWithoutCreatingActionableFallbackRows() {
        withdrawalRepository.order = withdrawal("WD-FINAL-1", "SUCCESS");

        ApiResult<PageResult<WithdrawalOrderView>> result = service.withdrawals(
                new WithdrawalQueryRequest(null, null, null, 1, 20, null, null, null));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getRecords())
                .extracting(WithdrawalOrderView::status)
                .containsExactly("SUCCESS");
    }

    @Test
    void withdrawalsDoesNotSeedWhenOnlyRealFinalRowsExist() {
        withdrawalRepository.order = withdrawal("WD-REAL-1", "SUCCESS");

        ApiResult<PageResult<WithdrawalOrderView>> result = service.withdrawals(
                new WithdrawalQueryRequest(null, null, null, 1, 20, null, null, null));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getRecords())
                .extracting(WithdrawalOrderView::withdrawalNo)
                .containsExactly("WD-REAL-1");
    }

    @Test
    void expiredFrozenLifecycleReturnsToReviewQueueWithAuditAndA4Event() {
        LocalDateTime dueAt = LocalDateTime.now().minusMinutes(1);
        withdrawalRepository.order = withLifecycle(
                withdrawal("WD-DUE-1", "FROZEN"), dueAt, "finance:oncall", "SEVEN_DAYS");
        withdrawalRepository.expiredLifecycleNos = List.of("WD-DUE-1");

        int released = service.releaseExpiredD2Lifecycles(LocalDateTime.now());

        assertThat(released).isEqualTo(1);
        assertThat(withdrawalRepository.lastStatus).isEqualTo("REVIEW_PENDING");
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("D2_WITHDRAWAL_REVIEW_DUE");
        assertThat(detailMap(audit.getValue().getDetail()))
                .containsEntry("from", "FROZEN")
                .containsEntry("to", "REVIEW_PENDING")
                .containsEntry("owner", "finance:oncall")
                .containsEntry("period", "SEVEN_DAYS");
        verify(eventOutboxService).publish(
                org.mockito.ArgumentMatchers.eq("WITHDRAWAL"),
                org.mockito.ArgumentMatchers.eq("WD-DUE-1"),
                org.mockito.ArgumentMatchers.eq("withdraw.review_due"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void expiredExtendedHoldReturnsToReviewQueueWithAuditAndA4Event() {
        LocalDateTime dueAt = LocalDateTime.now().minusMinutes(1);
        withdrawalRepository.order = withLifecycle(
                withdrawal("WD-DUE-HOLD-1", "EXTENDED_HOLD"), dueAt, "risk:oncall", "SEVEN_DAYS");
        withdrawalRepository.expiredLifecycleNos = List.of("WD-DUE-HOLD-1");

        int released = service.releaseExpiredD2Lifecycles(LocalDateTime.now());

        assertThat(released).isEqualTo(1);
        assertThat(withdrawalRepository.lastStatus).isEqualTo("REVIEW_PENDING");
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("D2_WITHDRAWAL_REVIEW_DUE");
        assertThat(detailMap(audit.getValue().getDetail()))
                .containsEntry("from", "EXTENDED_HOLD")
                .containsEntry("to", "REVIEW_PENDING")
                .containsEntry("owner", "risk:oncall")
                .containsEntry("period", "SEVEN_DAYS");
        verify(eventOutboxService).publish(
                org.mockito.ArgumentMatchers.eq("WITHDRAWAL"),
                org.mockito.ArgumentMatchers.eq("WD-DUE-HOLD-1"),
                org.mockito.ArgumentMatchers.eq("withdraw.review_due"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void expiredH1FastTrackRechecksAllGatesThenAutoApprovesWithCanonicalEvent() {
        LocalDateTime dueAt = LocalDateTime.now().minusMinutes(1);
        withdrawalRepository.order = withLifecycle(
                withdrawal("WD-H1-FAST-1", "EXTENDED_HOLD"), dueAt, "H1_PHASE_COOLDOWN", "H1:M3:P2");
        withdrawalRepository.expiredLifecycleNos = List.of("WD-H1-FAST-1");

        int released = service.releaseExpiredD2Lifecycles(LocalDateTime.now());

        assertThat(released).isEqualTo(1);
        assertThat(withdrawalRepository.lastStatus).isEqualTo("REVIEW_PASSED");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventOutboxService).publish(
                org.mockito.ArgumentMatchers.eq("WITHDRAWAL"),
                org.mockito.ArgumentMatchers.eq("WD-H1-FAST-1"),
                org.mockito.ArgumentMatchers.eq("withdraw.approved"),
                payload.capture());
        assertThat(payload.getValue()).containsOnlyKeys(
                "withdrawal_id", "amount", "currency", "state", "reason",
                "address_hash", "risk_score", "operator");
    }

    @Test
    void expiredH1FastTrackFreezesWhenK5ReviewAppearsDuringCooldown() {
        LocalDateTime dueAt = LocalDateTime.now().minusMinutes(1);
        withdrawalRepository.order = withLifecycle(
                withdrawal("WD-H1-K5-1", "EXTENDED_HOLD", new BigDecimal("1500.00")),
                dueAt, "H1_PHASE_COOLDOWN", "H1:M3:P2");
        withdrawalRepository.expiredLifecycleNos = List.of("WD-H1-K5-1");

        int released = service.releaseExpiredD2Lifecycles(LocalDateTime.now());

        assertThat(released).isEqualTo(1);
        assertThat(withdrawalRepository.lastStatus).isEqualTo("FROZEN");
        assertThat(withdrawalRepository.lastFailureReason).isEqualTo("K5_REVIEW:KR-D2-TEST");
        verify(eventOutboxService).publish(
                org.mockito.ArgumentMatchers.eq("WITHDRAWAL"),
                org.mockito.ArgumentMatchers.eq("WD-H1-K5-1"),
                org.mockito.ArgumentMatchers.eq("withdraw.frozen"),
                org.mockito.ArgumentMatchers.any());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
    }

    private void seedCanonicalD5() {
        configFacade.values.put("withdrawal.daily_count_limit", "2");
        configFacade.values.put("withdrawal.max_balance_pct", "0.80");
        configFacade.values.put("withdrawal.fee_rate", "0.02");
        configFacade.values.put("withdrawal.fee_min_usdt", "0.50");
        configFacade.values.put("withdrawal.fee_max_usdt", "20.00");
        configFacade.values.put("withdrawal.nex_fee_offset_rate", "0.40");
        configFacade.values.put("withdrawal.d5.version", "7");
        configFacade.values.put("growth.phase.withdraw_cooldown_days", "30");
        configFacade.values.put("growth.phase.withdraw_penalty_fee_rate", "0.20");
        configFacade.values.put("growth.phase.compliance_hold_enabled", "0");
        configFacade.values.put("H1.rhythm.totalMonths", "12");
        configFacade.values.put("H1.rhythm.currentMonth", "7");
        configFacade.values.put("growth.phase.current", "P3");
    }

    private WithdrawalLimitsUpdateRequest canonicalRequest(long version, String reason) {
        WithdrawalLimitsUpdateRequest request = new WithdrawalLimitsUpdateRequest();
        request.setExpectedVersion(version);
        request.setReason(reason);
        request.setOperator("superadmin");
        return request;
    }

    private void assertOverLimitReviewActionAudits(String action, String expectedStatus) {
        configFacade.values.put("withdrawal.daily_count_limit", "1");
        withdrawalRepository.order = withdrawal("WD-1", "REVIEWING", 2);
        WithdrawalReviewRequest request = new WithdrawalReviewRequest(action, "superadmin", "manual review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-1", "idem-review", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo(expectedStatus);
        assertThat(withdrawalRepository.lastStatus).isEqualTo(expectedStatus);

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService, atLeastOnce()).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("D2_WITHDRAWAL_REVIEW_" + action);
        assertThat(captor.getValue().getResult()).isEqualTo("SUCCESS");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("fromStatus", "REVIEWING")
                .containsEntry("toStatus", expectedStatus)
                .containsEntry("withdrawalCount24h", 2)
                .containsEntry("dailyLimitCount", 1)
                .containsEntry("idempotencyKey", "idem-review");
    }

    private static WithdrawalOrderView withdrawal(String withdrawalNo, String status) {
        return withdrawal(withdrawalNo, status, 1);
    }

    private static WithdrawalOrderView withdrawal(String withdrawalNo, String status, Integer withdrawalCount24h) {
        return withdrawal(withdrawalNo, status, "VERIFIED", "ACTIVE", 42, "", withdrawalCount24h);
    }

    private static WithdrawalOrderView withdrawal(String withdrawalNo, String status, BigDecimal amount) {
        return withdrawal(withdrawalNo, status, "VERIFIED", "ACTIVE", 42, "", "", 1, amount);
    }

    private static WithdrawalOrderView withdrawal(
            String withdrawalNo,
            String status,
            String kycStatus,
            String userStatus,
            Integer riskScore,
            String hitRules,
            Integer withdrawalCount24h) {
        return withdrawal(withdrawalNo, status, kycStatus, userStatus, riskScore, hitRules, "", withdrawalCount24h);
    }

    private static WithdrawalOrderView withdrawal(
            String withdrawalNo,
            String status,
            String kycStatus,
            String userStatus,
            Integer riskScore,
            String hitRules,
            String riskReason,
            Integer withdrawalCount24h) {
        return withdrawal(withdrawalNo, status, kycStatus, userStatus, riskScore, hitRules, riskReason, withdrawalCount24h, new BigDecimal("100.00"));
    }

    private static WithdrawalOrderView withdrawal(
            String withdrawalNo,
            String status,
            String kycStatus,
            String userStatus,
            Integer riskScore,
            String hitRules,
            String riskReason,
            Integer withdrawalCount24h,
            BigDecimal amount) {
        return new WithdrawalOrderView(
                1L,
                1001L,
                withdrawalNo,
                "USDT",
                "USDT-TRC20",
                amount,
                new BigDecimal("1.00"),
                "Txxx",
                null,
                null,
                status,
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                LocalDateTime.now(),
                LocalDateTime.now(),
                "U00001001",
                "测试用户",
                "138****0001",
                kycStatus,
                userStatus,
                riskScore,
                hitRules,
                riskReason,
                withdrawalCount24h,
                "",
                "");
    }

    private static WithdrawalOrderView withLifecycle(
            WithdrawalOrderView order,
            LocalDateTime holdUntil,
            String owner,
            String period) {
        return new WithdrawalOrderView(
                order.id(), order.userId(), order.withdrawalNo(), order.asset(), order.chain(), order.amount(), order.fee(),
                order.targetAddress(), order.riskDecisionId(), order.chainTxHash(), order.status(), order.chainSubmittedAt(),
                order.completedAt(), order.failedAt(), order.failureReason(), order.chainBroadcastAttempts(), order.nextBroadcastAt(),
                order.lastBroadcastError(), order.broadcastDeadAt(), order.createdAt(), order.updatedAt(), order.userNo(),
                order.nickname(), order.phoneMasked(), order.kycStatus(), order.userStatus(), order.riskScore(), order.hitRules(),
                order.riskReason(), order.withdrawalCount24h(), order.statusHistory(), order.auditTrail(),
                null, null, null, null, null,
                new BigDecimal("0.01"), new BigDecimal("1.00"), new BigDecimal("0.40"), new BigDecimal("0.40"),
                new BigDecimal("0.40"), new BigDecimal("0.60"), order.amount().subtract(new BigDecimal("0.60")),
                "10.0.0", holdUntil, owner, period, "REVIEW_PASSED",
                "LOW", 41, 73, 91, "pass");
    }

    private static final class FakePlatformConfigFacade implements PlatformConfigFacade {
        private final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public Optional<String> activeValue(String configKey) {
            return Optional.ofNullable(values.get(configKey));
        }

        @Override
        public void upsertAdminValue(String configKey, String configValue, String valueType, String configGroup, String remark) {
            values.put(configKey, configValue);
        }

        @Override
        public Map<String, String> activeValuesByGroup(String configGroup) {
            return values;
        }
    }

    private static final class FakeTreasuryCoverageFacade implements TreasuryCoverageFacade {
        private TreasuryCoverageSnapshot snapshot = new TreasuryCoverageSnapshot(new BigDecimal("100.00"), new BigDecimal("85.00"));

        @Override
        public TreasuryCoverageSnapshot snapshot() {
            return snapshot;
        }
    }

    private static final class FakeEmergencyControlRepository implements EmergencyControlRepository {
        private final Map<String, String> settings = new LinkedHashMap<>();
        private final List<Map<String, Object>> geoEndpointPolicies = new ArrayList<>();

        @Override
        public void ensureTables() {
        }

        @Override
        public List<Map<String, Object>> geoCountryPolicies() {
            return List.of();
        }

        @Override
        public void upsertGeoCountryPolicy(String countryCode, String countryName, String status, String reason, String operator) {
        }

        @Override
        public List<Map<String, Object>> geoEndpointCatalogs() {
            return List.of();
        }

        @Override
        public Optional<Map<String, Object>> geoEndpointCatalog(String endpointKey) {
            return Optional.empty();
        }

        @Override
        public List<Map<String, Object>> geoEndpointPolicies() {
            return geoEndpointPolicies;
        }

        @Override
        public void replaceGeoEndpointPolicies(String endpointKey, String endpointPath, String label, String biz, String domain,
                                               List<String> countryCodes, String source, String reason, String operator) {
        }

        @Override
        public List<Map<String, Object>> geoHits() {
            return List.of();
        }

        @Override
        public Map<String, Integer> geoEndpointHits() {
            return Map.of();
        }

        @Override
        public List<Map<String, Object>> geoEdgeMetrics() {
            return List.of();
        }

        @Override
        public Optional<String> settingValue(String settingKey) {
            return Optional.ofNullable(settings.get(settingKey));
        }

        @Override
        public void upsertSetting(String settingKey, String settingValue, String valueType, String groupCode, String remark, String operator) {
            settings.put(settingKey, settingValue);
        }

        @Override
        public Map<String, Object> tamperTrend(LocalDateTime now) {
            return Map.of();
        }

        @Override
        public List<Map<String, Object>> tamperPaths() {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> tamperAccounts() {
            return List.of();
        }

        @Override
        public void createTamperReport(String reportId, String window, boolean masked, String status,
                                       Map<String, Object> payload, String operator, String reason) {
        }

        @Override
        public List<Map<String, Object>> playbooks() {
            return List.of();
        }

        @Override
        public Optional<Map<String, Object>> playbook(String code) {
            return Optional.empty();
        }

        @Override
        public void createPlaybook(String code, String name, String scene, boolean emergency, String sla, String state,
                                   String owner, String notifyCampaignNo, String notifyTemplate, String rollback,
                                   boolean drillRequired, boolean draft, List<Map<String, Object>> sequence,
                                   String operator) {
        }

        @Override
        public void updatePlaybook(String code, String name, String scene, Boolean emergency, String sla, String state,
                                   String owner, String notifyCampaignNo, String notifyTemplate, String rollback,
                                   Boolean drillRequired, String summary, List<Map<String, Object>> sequence,
                                   String operator) {
        }

        @Override
        public void markPlaybookDrilled(String code, LocalDateTime drillAt, String operator) {
        }

        @Override
        public Optional<Map<String, Object>> executionByIdempotencyKey(String code, String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public Optional<Map<String, Object>> execution(String executionId) {
            return Optional.empty();
        }

        @Override
        public List<Map<String, Object>> executions(int limit) {
            return List.of();
        }

        @Override
        public void createExecution(Map<String, Object> row) {
        }

        @Override
        public boolean claimExecutionRollback(String executionId) {
            return false;
        }

        @Override
        public boolean completeExecutionRollback(String executionId, LocalDateTime rollbackAt, String reason,
                                                 List<Map<String, Object>> rollbackActions) {
            return false;
        }
    }

    private static final class FakeWithdrawalOrderRepository implements WithdrawalOrderRepository {
        private boolean c2FrozenByUserStatus;
        private WithdrawalOrderView order;
        private String lastStatus;
        private String lastStatusFilter;
        private Long lastUserIdFilter;
        private String lastKeywordFilter;
        private BigDecimal lastMinAmountFilter;
        private BigDecimal lastMaxAmountFilter;
        private Integer lastMinRiskScoreFilter;
        private String lastFailureReason;
        private boolean failK5Freeze;
        private List<String> expiredLifecycleNos = List.of();

        @Override
        public PageResult<WithdrawalOrderView> page(String status, Long userId, String keyword, BigDecimal minAmount,
                                                    BigDecimal maxAmount, Integer minRiskScore, int pageNum, int pageSize) {
            lastStatusFilter = status;
            lastUserIdFilter = userId;
            lastKeywordFilter = keyword;
            lastMinAmountFilter = minAmount;
            lastMaxAmountFilter = maxAmount;
            lastMinRiskScoreFilter = minRiskScore;
            if (order == null || (status != null && !status.equals(order.status()))) {
                return new PageResult<>(0, pageNum, pageSize, List.of());
            }
            return new PageResult<>(1, pageNum, pageSize, List.of(order));
        }

        @Override
        public Optional<WithdrawalOrderView> findByWithdrawalNo(String withdrawalNo) {
            return Optional.ofNullable(order);
        }

        @Override
        public Optional<String> findUserCountryCode(Long userId) {
            return Optional.of("US");
        }

        @Override
        public void updateStatus(String withdrawalNo, String status, String failureReason) {
            lastStatus = status;
            lastFailureReason = failureReason;
            order = new WithdrawalOrderView(
                    order.id(), order.userId(), order.withdrawalNo(), order.asset(), order.chain(), order.amount(), order.fee(),
                    order.targetAddress(), order.riskDecisionId(), order.chainTxHash(), status, order.chainSubmittedAt(),
                    order.completedAt(), order.failedAt(), failureReason, order.chainBroadcastAttempts(), order.nextBroadcastAt(),
                    order.lastBroadcastError(), order.broadcastDeadAt(), order.createdAt(), order.updatedAt(), order.userNo(),
                    order.nickname(), order.phoneMasked(), order.kycStatus(), order.userStatus(), order.riskScore(), order.hitRules(),
                    order.riskReason(), order.withdrawalCount24h(), order.statusHistory(), order.auditTrail());
        }

        @Override
        public boolean transitionStatus(String withdrawalNo, String expectedStatus, String newStatus, String failureReason) {
            if (order == null || !withdrawalNo.equals(order.withdrawalNo()) || !expectedStatus.equals(order.status())) {
                return false;
            }
            updateStatus(withdrawalNo, newStatus, failureReason);
            return true;
        }

        @Override
        public boolean transitionK5FrozenStatus(
                String withdrawalNo, String ticketId, String status, String failureReason) {
            if (order == null || !withdrawalNo.equals(order.withdrawalNo()) || !"FROZEN".equals(order.status())
                    || !("K5_REVIEW:" + ticketId).equals(order.failureReason())) {
                return false;
            }
            updateStatus(withdrawalNo, status, failureReason);
            return true;
        }

        @Override
        public boolean freezeForK5Review(String withdrawalNo, String expectedStatus, String ticketId) {
            if (failK5Freeze) return false;
            if (order == null || !withdrawalNo.equals(order.withdrawalNo()) || !expectedStatus.equals(order.status())) {
                return false;
            }
            updateStatus(withdrawalNo, "FROZEN", "K5_REVIEW:" + ticketId);
            return true;
        }

        @Override
        public int freezePendingByUserId(Long userId, String reason) {
            if (order == null || !userId.equals(order.userId())) {
                return 0;
            }
            updateStatus(order.withdrawalNo(), "FROZEN", reason);
            return 1;
        }

        @Override
        public int restoreFrozenByUserStatus(Long userId) {
            return 0;
        }

        @Override
        public boolean isFrozenByUserStatus(String withdrawalNo) {
            return c2FrozenByUserStatus;
        }

        @Override
        public List<String> findExpiredLifecycleNos(LocalDateTime now) {
            return expiredLifecycleNos;
        }

        @Override
        public boolean releaseExpiredLifecycle(
                String withdrawalNo,
                String expectedStatus,
                String newStatus,
                String failureReason,
                LocalDateTime now) {
            return transitionStatus(withdrawalNo, expectedStatus, newStatus, failureReason);
        }

        @Override
        public long countD2ActionableWithdrawals() {
            return order != null && isActionableStatus(order.status()) ? 1 : 0;
        }

        private boolean isActionableStatus(String status) {
            return List.of("REVIEWING", "DELAYED", "FROZEN", "PENDING_CHAIN", "CHAIN_SUBMITTED", "DEAD")
                    .contains(status);
        }
    }

    private static final class FakeRiskKycReviewFacade implements RiskKycReviewFacade {
        private String lastWithdrawalNo;
        private BigDecimal lastAmountUsdt;
        private String lastOperator;

        @Override
        public KycReviewTriggerResult triggerLargeWithdrawalReview(
                String userNo,
                BigDecimal amountUsdt,
                String kycStatus,
                String withdrawalNo,
                String operator,
                String reason) {
            lastWithdrawalNo = withdrawalNo;
            lastAmountUsdt = amountUsdt;
            lastOperator = operator;
            if (amountUsdt != null && amountUsdt.compareTo(new BigDecimal("1000")) >= 0) {
                return new KycReviewTriggerResult(true, true, "KR-D2-TEST", "K5_LARGE_WITHDRAWAL_REVIEW_REQUIRED");
            }
            return KycReviewTriggerResult.notRequired();
        }

        @Override
        public KycReviewTriggerResult triggerLargeExchangeReview(
                String userNo,
                BigDecimal amountUsdt,
                String kycStatus,
                String exchangeNo,
                String operator,
                String reason) {
            return KycReviewTriggerResult.notRequired();
        }
    }

    private static final class FakeDepositOpsRepository implements DepositOpsRepository {
        private List<DepositAggregateView> aggregates = List.of();
        private final Map<String, String> writeoffs = new LinkedHashMap<>();
        private Collection<String> lastFlowStatuses = List.of("UNSET");
        private String lastWriteoffMethod;
        private String lastWriteoffEvidenceRef;
        private DepositChargebackView chargeback;
        private TopupChargebackRecoveryCommand lastRecoveryCommand;
        private TopupChargebackRecoveryResult recoveryResult;
        private List<DepositBinRiskView> binRows = List.of();

        @Override
        public List<DepositAggregateView> aggregateToday() {
            return aggregates;
        }

        @Override
        public PageResult<DepositFlowView> pageFlows(Collection<String> statuses, Long userId, String keyword, int pageNum, int pageSize) {
            lastFlowStatuses = statuses == null ? List.of() : List.copyOf(statuses);
            return new PageResult<>(0, pageNum, pageSize, List.of());
        }

        @Override
        public boolean hasReconciliationWriteoff(String channelCode, LocalDate reconcileDate) {
            return writeoffs.containsKey(channelCode + "|" + reconcileDate);
        }

        @Override
        public void writeoffReconciliation(String channelCode, LocalDate reconcileDate, String method, String evidenceRef,
                                           String operator, String reason, String idempotencyKey) {
            writeoffs.put(channelCode + "|" + reconcileDate, reason);
            lastWriteoffMethod = method;
            lastWriteoffEvidenceRef = evidenceRef;
        }

        @Override
        public List<DepositBinRiskView> failedPaymentRiskRows(int threshold) {
            return binRows;
        }

        @Override
        public List<DepositChargebackView> chargebacks() {
            return List.of();
        }

        @Override
        public Optional<DepositChargebackView> findChargeback(String caseNo) {
            return Optional.ofNullable(chargeback != null && caseNo.equals(chargeback.caseNo()) ? chargeback : null);
        }

        @Override
        public BigDecimal feeBufferBalance() {
            return BigDecimal.ZERO;
        }

        @Override
        public TopupChargebackRecoveryResult recoverChargeback(TopupChargebackRecoveryCommand command) {
            lastRecoveryCommand = command;
            return recoveryResult;
        }

        @Override
        public void syncAutomaticRiskLocks(int threshold, int lockHours) {
        }

        @Override
        public List<ffdd.opsconsole.finance.domain.TopupRiskLockSnapshot> activeRiskLockSnapshotsForUpdate() {
            return List.of();
        }

        @Override
        public void setRiskLock(String targetType, String targetValue, boolean locked, int lockHours,
                                String reason, String operator) {
        }

    }
}
