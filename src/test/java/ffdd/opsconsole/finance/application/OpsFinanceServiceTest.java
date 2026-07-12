package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
import ffdd.opsconsole.finance.domain.WithdrawalOrderRepository;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.dto.TopupCommandRequest;
import ffdd.opsconsole.finance.dto.WithdrawalParamUpdateRequest;
import ffdd.opsconsole.finance.dto.WithdrawalQueryRequest;
import ffdd.opsconsole.finance.dto.WithdrawalReviewRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.risk.domain.RiskRuleView;
import ffdd.opsconsole.risk.facade.KycReviewTriggerResult;
import ffdd.opsconsole.risk.facade.RiskKycReviewFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final ffdd.opsconsole.platform.mapper.AuditObjectLockMapper lockMapper =
            mock(ffdd.opsconsole.platform.mapper.AuditObjectLockMapper.class);
    private final RiskDisclosureGateFacade disclosureGateFacade = mock(RiskDisclosureGateFacade.class);
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
                    ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
                    lockMapper,
                    disclosureGateFacade);

    @BeforeEach
    void setUpRiskDefaults() {
        when(riskOpsRepository.withdrawRules()).thenReturn(List.of());
        when(lockMapper.countActiveByTarget(anyString(), anyString(), anyString())).thenReturn(0);
        when(disclosureGateFacade.checkUserGate(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString()))
                .thenReturn(ApiResult.ok(null));
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
                seedPolicy,
                lockMapper,
                disclosureGateFacade);
    }

    @Test
    void withdrawalParamsIncludeCoverageAndConfigValues() {
        configFacade.values.put("withdrawal.daily_count_limit", "2");
        configFacade.values.put("withdrawal.max_balance_pct", "0.75");
        configFacade.values.put("H1.rhythm.totalMonths", "12");
        configFacade.values.put("H1.rhythm.currentMonth", "11");
        configFacade.values.put("H1.rhythm.phaseProgressPct", "92");
        configFacade.values.put("growth.phase.current", "P6");
        configFacade.values.put("growth.withdraw_nex_gate.min_balance_nex", "250");
        configFacade.values.put("growth.withdraw_nex_gate.hold_days", "14");
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("110.00"), new BigDecimal("85.00"));

        ApiResult<Map<String, Object>> result = service.withdrawalParams();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("dailyLimitCount", 2)
                .containsEntry("maxBalanceRatio", new BigDecimal("0.75"))
                .containsEntry("coverageRatio", new BigDecimal("110.00"))
                .containsEntry("redlinePct", new BigDecimal("85.00"));
        assertThat(result.getData().get("h1Rhythm"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("currentMonth", 11)
                .containsEntry("currentPhase", "P6");
        assertThat(result.getData().get("withdrawNexGate"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("minBalanceNex", new BigDecimal("250"))
                .containsEntry("holdDays", 14);
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
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("D5_WITHDRAWAL_PARAM_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-d5");
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
    void reviewWithdrawalApprovesReviewingOrderAndAudits() {
        withdrawalRepository.order = withdrawal("WD-1", "REVIEWING");
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "manual review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-1", "idem-review", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("PENDING_CHAIN");
        assertThat(withdrawalRepository.lastStatus).isEqualTo("PENDING_CHAIN");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("D2_WITHDRAWAL_REVIEW_APPROVE");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("fromStatus", "REVIEWING")
                .containsEntry("toStatus", "PENDING_CHAIN")
                .containsEntry("idempotencyKey", "idem-review");
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
        withdrawalRepository.order = withdrawal("WD-LARGE-1", "REVIEWING", new BigDecimal("8200.00"));
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "large withdrawal review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-LARGE-1", "idem-review", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_K5_REVIEW_REQUIRED");
        assertThat(withdrawalRepository.lastStatus).isEqualTo("FROZEN");
        assertThat(withdrawalRepository.lastFailureReason).isEqualTo("K5_REVIEW:KR-D2-TEST");
        assertThat(riskKycReviewFacade.lastWithdrawalNo).isEqualTo("WD-LARGE-1");
        assertThat(riskKycReviewFacade.lastAmountUsdt).isEqualByComparingTo("8200.00");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("D2_WITHDRAWAL_K5_REVIEW_REQUIRED");
        assertThat(captor.getValue().getResult()).isEqualTo("BLOCKED");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("toStatus", "FROZEN")
                .containsEntry("blockedReason", "WITHDRAWAL_K5_REVIEW_REQUIRED")
                .containsEntry("k5TicketId", "KR-D2-TEST")
                .containsEntry("k5Created", true);
    }

    @Test
    void reviewWithdrawalRejectsApproveWhenK3ActiveRuleMatches() {
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

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_RISK_HIT_BLOCKED");
        assertThat(withdrawalRepository.lastStatus).isNull();
        assertThat(riskKycReviewFacade.lastWithdrawalNo).isNull();
        verify(riskOpsRepository).recordWithdrawRuleHit("WD-K3-1", "U00001001", new BigDecimal("150.00"), rule);

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("D2_WITHDRAWAL_REVIEW_BLOCKED");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("blockedReason", "WITHDRAWAL_RISK_HIT_BLOCKED")
                .containsEntry("statusUnchanged", true);
    }

    @Test
    void reviewWithdrawalHonorsK3ComparisonOperator() {
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

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_RISK_HIT_BLOCKED");
        verify(riskOpsRepository).recordWithdrawRuleHit("WD-K3-LOW-1", "U00001001", new BigDecimal("80.00"), rule);
    }

    @Test
    void reviewWithdrawalContinuesK3RulesAfterNonBlockingMatch() {
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

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_RISK_HIT_BLOCKED");
        verify(riskOpsRepository).recordWithdrawRuleHit("WD-K3-MULTI-1", "U00001001", new BigDecimal("150.00"), auditOnlyRule);
        verify(riskOpsRepository).recordWithdrawRuleHit("WD-K3-MULTI-1", "U00001001", new BigDecimal("150.00"), blockingRule);
        assertThat(withdrawalRepository.lastStatus).isNull();
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
        assertThat(withdrawalRepository.lastStatus).isEqualTo("PENDING_CHAIN");
    }

    @Test
    void reviewWithdrawalAllowsDelayWhenD5DailyLimitExceededAndAudits() {
        assertOverLimitReviewActionAudits("DELAY", "DELAYED");
    }

    @Test
    void reviewWithdrawalAllowsFreezeWhenD5DailyLimitExceededAndAudits() {
        assertOverLimitReviewActionAudits("FREEZE", "FROZEN");
    }

    @Test
    void reviewWithdrawalAllowsRejectWhenD5DailyLimitExceededAndAudits() {
        assertOverLimitReviewActionAudits("REJECT", "REJECTED");
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
    void reviewWithdrawalRejectsApproveWhenRiskHitIsPresent() {
        withdrawalRepository.order = withdrawal("WD-1", "REVIEWING", "VERIFIED", "ACTIVE", 69, "velocity:FREEZE", 1);
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "manual review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-1", "idem-review", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_RISK_HIT_BLOCKED");
        assertThat(withdrawalRepository.lastStatus).isNull();
    }

    @Test
    void reviewWithdrawalRejectsApproveWhenK3RiskReasonIsPresent() {
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

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("WITHDRAWAL_RISK_HIT_BLOCKED");
        assertThat(withdrawalRepository.lastStatus).isNull();

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("riskReason", "单笔大额提现转人工")
                .containsEntry("blockedReason", "WITHDRAWAL_RISK_HIT_BLOCKED");
    }

    @Test
    void reviewWithdrawalAllowsApproveAfterD5DailyLimitRaised() {
        configFacade.values.put("withdrawal.daily_count_limit", "2");
        withdrawalRepository.order = withdrawal("WD-1", "REVIEWING", 2);
        WithdrawalReviewRequest request = new WithdrawalReviewRequest("APPROVE", "superadmin", "manual review");

        ApiResult<WithdrawalOrderView> result = service.reviewWithdrawal("WD-1", "idem-review", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("PENDING_CHAIN");
        assertThat(withdrawalRepository.lastStatus).isEqualTo("PENDING_CHAIN");
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
        configFacade.values.put("finance.topup.bin.bin-4716.locked", "true");

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
    void topupChannelWritePersistsConfigAndAudits() {
        TopupCommandRequest request = new TopupCommandRequest(null, false, "pause incident channel", "superadmin");

        ApiResult<Map<String, Object>> result = service.updateTopupChannelEnabled("trc20", "idem-d1", request);

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("finance.topup.channel.trc20.enabled", "false");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("D1_TOPUP_CHANNEL_STATUS_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-d1");
    }

    @Test
    void withdrawalsPassesServerSideAmountAndRiskFiltersToRepository() {
        withdrawalRepository.order = withdrawal("WD-1", "REVIEWING");

        ApiResult<PageResult<WithdrawalOrderView>> result = service.withdrawals(
                new WithdrawalQueryRequest("REVIEWING", 1001L, "WD", 2, 50, new BigDecimal("1000"), new BigDecimal("5000"), 70));

        assertThat(result.getCode()).isZero();
        assertThat(withdrawalRepository.lastStatusFilter).isEqualTo("REVIEWING");
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
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
        verify(auditLogService).record(captor.capture());
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
        public void markExecutionRolledBack(String executionId, LocalDateTime rollbackAt, String reason,
                                            List<Map<String, Object>> rollbackActions) {
        }
    }

    private static final class FakeWithdrawalOrderRepository implements WithdrawalOrderRepository {
        private WithdrawalOrderView order;
        private String lastStatus;
        private String lastStatusFilter;
        private Long lastUserIdFilter;
        private String lastKeywordFilter;
        private BigDecimal lastMinAmountFilter;
        private BigDecimal lastMaxAmountFilter;
        private Integer lastMinRiskScoreFilter;
        private String lastFailureReason;

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
        public int freezePendingByUserId(Long userId, String reason) {
            if (order == null || !userId.equals(order.userId())) {
                return 0;
            }
            updateStatus(order.withdrawalNo(), "FROZEN", reason);
            return 1;
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

        @Override
        public List<DepositAggregateView> aggregateToday() {
            return aggregates;
        }

        @Override
        public PageResult<DepositFlowView> pageFlows(Collection<String> statuses, Long userId, String keyword, int pageNum, int pageSize) {
            return new PageResult<>(0, pageNum, pageSize, List.of());
        }

        @Override
        public long cardPaidCountToday() {
            return 0;
        }

        @Override
        public BigDecimal cardPaidAmountToday() {
            return BigDecimal.ZERO;
        }

        @Override
        public boolean hasReconciliationWriteoff(String channelCode, LocalDate reconcileDate) {
            return writeoffs.containsKey(channelCode + "|" + reconcileDate);
        }

        @Override
        public void writeoffReconciliation(String channelCode, LocalDate reconcileDate, String operator, String reason, String idempotencyKey) {
            writeoffs.put(channelCode + "|" + reconcileDate, reason);
        }

        @Override
        public List<DepositBinRiskView> failedPaymentRiskRows(int threshold) {
            return List.of();
        }

        @Override
        public List<DepositChargebackView> chargebacks() {
            return List.of();
        }

        @Override
        public Optional<DepositChargebackView> findChargeback(String caseNo) {
            return Optional.empty();
        }

        @Override
        public int markChargebackRefunded(String caseNo, String reason) {
            return 0;
        }

    }
}
