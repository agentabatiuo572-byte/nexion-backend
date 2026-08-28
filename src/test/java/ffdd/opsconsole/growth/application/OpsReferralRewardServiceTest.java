package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import ffdd.opsconsole.growth.dto.ReferralSettlementRunRequest;
import ffdd.opsconsole.growth.dto.AcceptanceSandboxReferralSettlementRequest;
import ffdd.opsconsole.growth.dto.ReferralRewardParamUpdateRequest;
import ffdd.opsconsole.growth.domain.ReferralRewardPublicConfigView;
import ffdd.opsconsole.growth.mapper.ReferralRewardMapper;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.mock.env.MockEnvironment;

class OpsReferralRewardServiceTest {
    private final ReferralRewardMapper mapper = mock(ReferralRewardMapper.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final TreasuryLedgerPostingFacade ledger = mock(TreasuryLedgerPostingFacade.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final TreasuryCoverageFacade coverage = mock(TreasuryCoverageFacade.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy = mock(OpsReadTimeSeedPolicy.class);
    private final EarningsReleaseService earningsRelease = mock(EarningsReleaseService.class);
    private final MockEnvironment environment = productionEnvironment();
    private final H8AcceptanceSandboxCommandService sandboxCommands = mock(H8AcceptanceSandboxCommandService.class);
    private final H8AcceptanceSandboxAuditService sandboxAudit = mock(H8AcceptanceSandboxAuditService.class);
    private final OpsReferralRewardService service = new OpsReferralRewardService(
            mapper, config, ledger, audit, idempotency, coverage, outbox, readTimeSeedPolicy, earningsRelease, environment,
            new H8AcceptanceSandboxRunScope(environment), sandboxCommands, sandboxAudit);

    private static MockEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }

    @BeforeEach
    void setUp() {
        A2ReplayContext.enterReplay();
        when(mapper.lockRewardMutation()).thenReturn("H8_REWARD");
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(Map.class), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        when(sandboxCommands.execute(anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
        when(config.activeValue("K.rewards.welcomeGift.usdtAmount")).thenReturn(Optional.of("5"));
        when(config.activeValue("K.rewards.welcomeGift.nexAmount")).thenReturn(Optional.of("20"));
        when(config.activeValue("K.rewards.welcomeGift.lockMode")).thenReturn(Optional.of("risk_bucket"));
        when(config.activeValue("K.rewards.inviterReward.nexAmount")).thenReturn(Optional.of("10"));
        when(config.activeValue("K.rewards.referral.effectiveAt")).thenReturn(Optional.of("2026-07-17T00:00:00Z"));
        when(config.activeValue("K.rewards.referral.version")).thenReturn(Optional.of("1"));
        when(config.activeValueForUpdate("K.rewards.referral.version")).thenReturn(Optional.of("1"));
        when(config.activeValue("H1.rhythm.totalMonths")).thenReturn(Optional.of("12"));
        when(config.activeValue("H1.rhythm.currentMonth")).thenReturn(Optional.of("7"));
        when(config.activeValue("growth.phase.current")).thenReturn(Optional.of("P4"));
        when(config.activeValue("growth.phase.month.7.newUserBonusMultiplier")).thenReturn(Optional.of("1"));
        when(config.activeValue("growth.phase.month.7.inviteRewardMultiplier")).thenReturn(Optional.of("1"));
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(new BigDecimal("100"), new BigDecimal("85")));
    }

    @AfterEach
    void tearDown() {
        A2ReplayContext.exitReplay();
        SecurityContextHolder.clearContext();
    }

    @Test
    void directSettlementCallCannotBypassA2MakerChecker() {
        A2ReplayContext.exitReplay();
        try {
            assertThatThrownBy(() -> service.runSettlements("idem-ref-direct",
                    request(10, "direct settlement must be rejected")))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("A2_CONFIRMATION_REQUIRED");

            verify(mapper, never()).lockRewardMutation();
            verify(earningsRelease, never()).creditReward(any(), anyString(), anyString(), anyString(),
                    any(), anyString(), anyString());
            verify(ledger, never()).postLedgerEntry(anyString(), any(), anyString(), anyString(), anyString(),
                    any(), anyString(), anyString());
        } finally {
            A2ReplayContext.enterReplay();
        }
    }

    @Test
    void strictSandboxProfileBlocksProductionSettlementBeforeAnyWriteOrAudit() {
        environment.setActiveProfiles("test");

        assertThatThrownBy(() -> service.runSettlements("idem-ref-sandbox-block",
                new ReferralSettlementRunRequest(10, "strict sandbox must not write production",
                        "superadmin", 1L, 7, "not-used")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("H8_PRODUCTION_SETTLEMENT_FORBIDDEN_IN_SANDBOX");

        verify(mapper, never()).lockRewardMutation();
        verify(audit, never()).recordRequiredInNewTransaction(any());
    }

    @Test
    void acceptanceSandboxSettlementRequiresRunScopedFixtureId() {
        environment.setActiveProfiles("test");

        assertThatThrownBy(() -> service.runAcceptanceSandboxSettlement("idem-h8-run-required",
                new AcceptanceSandboxReferralSettlementRequest(null, 22L,
                        "sandbox fixture needs a run id", "acceptance-runner")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("H8_SANDBOX_RUN_ID_REQUIRED");

        verify(mapper, never()).lockRewardMutation();
    }

    @Test
    void acceptanceSandboxSettlementRejectsAnotherLegalRunBeforeIdempotencyOrAudit() {
        environment.setActiveProfiles("test");
        environment.setProperty("NEXION_ACCEPTANCE_RUN_ID", "RUN-H8-SERVER");

        assertThatThrownBy(() -> service.runAcceptanceSandboxSettlement("idem-h8-other-run",
                new AcceptanceSandboxReferralSettlementRequest("RUN-H8-OTHER", 22L,
                        "another legal run must not select fixtures", "acceptance-runner")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("H8_SANDBOX_RUN_ID_MISMATCH");

        verify(mapper, never()).lockRewardMutation();
        verify(sandboxCommands, never()).execute(anyString(), anyString(), anyString(), any());
        verify(sandboxAudit, never()).recordRejected(any(), any(), any(), any(), any());
    }

    @Test
    void rejectedSandboxSettlementAuditsAuthenticatedActorInsteadOfSpoofedRequestOperator() {
        environment.setActiveProfiles("test");
        environment.setProperty("NEXION_ACCEPTANCE_RUN_ID", "RUN-H8-SERVER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("812", null, List.of()));

        assertThatThrownBy(() -> service.runAcceptanceSandboxSettlement("idem-h8-rejected-actor",
                new AcceptanceSandboxReferralSettlementRequest("RUN-H8-SERVER", null,
                        "invalid sandbox settlement is audited", "spoofed-operator")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("H8_SANDBOX_INVITED_USER_REQUIRED");

        verify(sandboxAudit).recordRejected(eq("RUN-H8-SERVER"), eq("idem-h8-rejected-actor"),
                eq("admin:812"), eq("invalid sandbox settlement is audited"), any(RuntimeException.class));
    }

    @Test
    void publicProjectionUsesPhaseAdjustedSettlementAmountsInsteadOfPrototypeDefaults() {
        when(config.activeValue("growth.phase.month.7.newUserBonusMultiplier"))
                .thenReturn(Optional.of("1.5"));
        when(config.activeValue("growth.phase.month.7.inviteRewardMultiplier"))
                .thenReturn(Optional.of("2"));

        ReferralRewardPublicConfigView result = service.publicConfig();

        assertThat(result.rhythmMonth()).isEqualTo(7);
        assertThat(result.welcomeGift().lockMode()).isEqualTo("risk_bucket");
        assertThat(result.welcomeGift().usdtAmount()).isEqualByComparingTo("7.5");
        assertThat(result.welcomeGift().nexAmount()).isEqualByComparingTo("30");
        assertThat(result.inviterReward().nexAmount()).isEqualByComparingTo("20");
        assertThat(result.welcomeGift().usdtAmount().scale()).isEqualTo(6);
        assertThat(result.welcomeGift().nexAmount().scale()).isEqualTo(6);
        assertThat(result.inviterReward().nexAmount().scale()).isEqualTo(6);
        assertThat(result.sources()).contains("nx_user.sponsor_user_id");
    }

    @Test
    void settlesRealSponsorChainExactlyOnceAndCreditsBothWallets() {
        when(mapper.findPendingReferrals(any(LocalDateTime.class), eq("PRODUCTION"), eq(0), eq(true), eq(10), eq(null)))
                .thenReturn(List.of(new ReferralRewardMapper.ReferralRow(22L, 11L)));
        when(mapper.insertSettlement(anyString(), eq(22L), eq(11L), any(), any(), any(),
                eq("risk_bucket"), anyString(), anyString(), anyString(), anyString(),
                 any(LocalDateTime.class), eq("PRODUCTION"), eq(0), eq(true))).thenReturn(1);

        Map<String, Object> result = service.runSettlements("idem-ref-1",
                request(10, "manual reconciliation"));

        assertThat(result).containsEntry("settled", 1).containsEntry("skipped", 0);
        verify(mapper).lockRewardMutation();
        verify(earningsRelease).creditReward(eq(22L), eq("H8_REFERRAL"), anyString(), eq("USDT"),
                decimal("5"), eq("PRODUCTION"), anyString());
        verify(earningsRelease).creditReward(eq(22L), eq("H8_REFERRAL"), anyString(), eq("NEX"),
                decimal("20"), eq("PRODUCTION"), anyString());
        verify(earningsRelease).creditReward(eq(11L), eq("H8_REFERRAL"), anyString(), eq("NEX"),
                decimal("10"), eq("PRODUCTION"), anyString());
        verify(ledger).postLedgerEntry(anyString(), eq(22L), eq("REFERRAL_REWARD"), eq("USDT"), eq("IN"), decimal("5"), eq("SUCCESS"), anyString());
        verify(ledger).postLedgerEntry(anyString(), eq(11L), eq("REFERRAL_REWARD"), eq("NEX"), eq("IN"), decimal("10"), eq("SUCCESS"), anyString());
        verify(audit, times(2)).recordRequired(any());
    }

    @Test
    void developmentProfileSettlesPhysicalDevelopmentAccountsThroughCanonicalProductionLedger() {
        environment.setActiveProfiles("dev");
        when(mapper.findPendingReferrals(any(LocalDateTime.class), eq("PRODUCTION"), eq(1), eq(true), eq(10), eq(null)))
                .thenReturn(List.of(new ReferralRewardMapper.ReferralRow(22L, 11L)));
        when(mapper.insertSettlement(anyString(), eq(22L), eq(11L), any(), any(), any(),
                eq("risk_bucket"), anyString(), anyString(), anyString(), anyString(),
                any(LocalDateTime.class), eq("PRODUCTION"), eq(1), eq(true))).thenReturn(1);

        Map<String, Object> result = service.runSettlements("idem-ref-dev-canonical",
                request(10, "development canonical referral settlement"));

        assertThat(result).containsEntry("settled", 1).containsEntry("skipped", 0);
        verify(earningsRelease).creditReward(eq(11L), eq("H8_REFERRAL"), anyString(), eq("NEX"),
                decimal("10"), eq("PRODUCTION"), anyString());
    }

    @Test
    void acceptanceSandboxSettlementUsesMockSourceAndNeverCallsProductionLedgerFacade() {
        environment.setActiveProfiles("test");
        environment.setProperty("NEXION_ACCEPTANCE_RUN_ID", "run-h8-acceptance");
        when(mapper.findPendingSandboxReferral(any(LocalDateTime.class), anyString(), eq(22L)))
                .thenReturn(List.of(new ReferralRewardMapper.ReferralRow(22L, 11L)));
        when(mapper.insertSandboxSettlement(anyString(), eq(22L), eq(11L), any(), any(), any(),
                eq("risk_bucket"), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(LocalDateTime.class))).thenReturn(1);
        when(mapper.insertSandboxLedger(anyString(), anyString(), anyLong(), anyString(), any(), anyString())).thenReturn(1);

        Map<String, Object> result = service.runAcceptanceSandboxSettlement("idem-h8-sandbox-1",
                new AcceptanceSandboxReferralSettlementRequest("run-h8-acceptance",
                        22L,
                        "acceptance fixture settlement", "acceptance-runner"));

        assertThat(result).containsEntry("settled", 1).containsEntry("source", "mock")
                .containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("sourceType", "MOCK_REFERRAL");
        verify(earningsRelease, never()).creditReward(any(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString());
        verify(mapper, times(3)).insertSandboxLedger(anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        verify(ledger, never()).postLedgerEntry(anyString(), any(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString());
        verify(idempotency, never()).execute(eq("H8_ACCEPTANCE_SANDBOX_REFERRAL"), anyString(), anyString(), eq(Map.class), any());
        verify(audit, never()).recordRequired(any());
        verify(audit, never()).recordRequiredInNewTransaction(any());
        verify(outbox, never()).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void acceptanceSandboxSettlementDoesNotDependOnProductionCoverage() {
        environment.setActiveProfiles("test");
        environment.setProperty("NEXION_ACCEPTANCE_RUN_ID", "run-h8-acceptance");
        when(mapper.findPendingSandboxReferral(any(LocalDateTime.class), anyString(), eq(22L)))
                .thenReturn(List.of(new ReferralRewardMapper.ReferralRow(22L, 11L)));
        when(mapper.insertSandboxSettlement(anyString(), eq(22L), eq(11L), any(), any(), any(),
                eq("risk_bucket"), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(LocalDateTime.class))).thenReturn(1);
        when(mapper.insertSandboxLedger(anyString(), anyString(), anyLong(), anyString(), any(), anyString())).thenReturn(1);
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, false));

        Map<String, Object> result = service.runAcceptanceSandboxSettlement("idem-h8-sandbox-no-b1",
                new AcceptanceSandboxReferralSettlementRequest("run-h8-acceptance",
                        22L,
                        "sandbox cannot consume production coverage", "acceptance-runner"));

        assertThat(result).containsEntry("settled", 1).containsEntry("sourceEnvironment", "SANDBOX");
        verify(coverage, never()).snapshot();
    }

    @Test
    void finalAtomicEligibilityFailureNeverCreditsWalletOrLedger() {
        when(mapper.findPendingReferrals(any(LocalDateTime.class), eq("PRODUCTION"), eq(0), eq(true), eq(10), eq(null)))
                .thenReturn(List.of(new ReferralRewardMapper.ReferralRow(22L, 11L)));

        Map<String, Object> result = service.runSettlements("idem-ref-race",
                request(10, "final risk race verification"));

        assertThat(result).containsEntry("settled", 0).containsEntry("skipped", 1);
        verify(earningsRelease, never()).creditReward(any(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString());
        verify(ledger, never()).postLedgerEntry(anyString(), any(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString());
    }

    @Test
    void settlementTransactionUsesSerializableIsolation() throws Exception {
        Transactional transactional = OpsReferralRewardService.class
                .getMethod("runSettlements", String.class, ReferralSettlementRunRequest.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.isolation()).isEqualTo(Isolation.SERIALIZABLE);
    }

    @Test
    void overviewExposesTheSamePhaseAdjustedAmountsUsedBySettlement() {
        when(config.activeValue("growth.phase.month.7.newUserBonusMultiplier"))
                .thenReturn(Optional.of("1.5"));
        when(config.activeValue("growth.phase.month.7.inviteRewardMultiplier"))
                .thenReturn(Optional.of("2"));

        Map<String, Object> overview = service.overview();

        assertThat(overview).containsEntry("rhythmMonth", 7);
        assertThat(overview).containsEntry("newcomerMultiplier", new BigDecimal("1.5"));
        assertThat(overview).containsEntry("inviterMultiplier", new BigDecimal("2"));
        Map<String, Object> effective = (Map<String, Object>) overview.get("effectiveRewards");
        assertThat((BigDecimal) effective.get("newcomer.usdt")).isEqualByComparingTo("7.5");
        assertThat((BigDecimal) effective.get("newcomer.nex")).isEqualByComparingTo("30");
        assertThat((BigDecimal) effective.get("inviter.nex")).isEqualByComparingTo("20");
    }

    @Test
    void missingDatabaseMutexFailsClosedBeforeSettlement() {
        when(mapper.lockRewardMutation()).thenReturn(null);

        assertThatThrownBy(() -> service.runSettlements("idem-ref-no-mutex",
                request(10, "mutex health verification")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("H8_REWARD_MUTEX_UNAVAILABLE");

        verify(mapper, never()).findPendingReferrals(any(LocalDateTime.class), anyString(), anyInt(), anyBoolean(), anyInt(), any());
    }

    @Test
    void postSettlementCoverageDropRollsBackTheBatch() {
        when(mapper.findPendingReferrals(any(LocalDateTime.class), eq("PRODUCTION"), eq(0), eq(true), eq(10), eq(null)))
                .thenReturn(List.of(new ReferralRewardMapper.ReferralRow(22L, 11L)));
        when(mapper.insertSettlement(anyString(), eq(22L), eq(11L), any(), any(), any(),
                eq("risk_bucket"), anyString(), anyString(), anyString(), anyString(),
                 any(LocalDateTime.class), eq("PRODUCTION"), eq(0), eq(true))).thenReturn(1);
        when(coverage.snapshot()).thenReturn(
                new TreasuryCoverageSnapshot(new BigDecimal("100"), new BigDecimal("85")),
                new TreasuryCoverageSnapshot(new BigDecimal("80"), new BigDecimal("85")));

        assertThatThrownBy(() -> service.runSettlements("idem-ref-post-b1",
                request(10, "post award coverage verification")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("B1_COVERAGE_BELOW_REDLINE");
    }

    @Test
    void unavailableOrUnreliableCoverageFailsClosed() {
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, false));

        assertThatThrownBy(() -> service.runSettlements("idem-ref-b1-unavailable",
                request(10, "coverage source verification")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("B1_COVERAGE_DATA_UNAVAILABLE");

        verify(mapper, never()).findPendingReferrals(any(LocalDateTime.class), anyString(), anyInt(), anyBoolean(), anyInt(), any());
    }

    @Test
    void rejectsRewardIncreaseWhenB1CoverageIsBelowRedline() {
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(new BigDecimal("80"), new BigDecimal("85")));

        assertThatThrownBy(() -> service.updateParam("newcomer.usdt", "idem-ref-b1",
                new ReferralRewardParamUpdateRequest("newcomer.usdt", "6", 1L,
                        "raise welcome gift budget", "superadmin")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("B1_COVERAGE_BELOW_REDLINE");

        verify(config, never()).upsertAdminValue(eq("K.rewards.welcomeGift.usdtAmount"), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void rejectsSettlementRunWhenB1CoverageIsBelowRedline() {
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(new BigDecimal("80"), new BigDecimal("85")));

        assertThatThrownBy(() -> service.runSettlements("idem-ref-b1-run",
                request(10, "manual reconciliation")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("B1_COVERAGE_BELOW_REDLINE");

        verify(mapper, never()).findPendingReferrals(any(LocalDateTime.class), anyString(), anyInt(), anyBoolean(), anyInt(), any());
    }

    @Test
    void missingMoneyConfigurationFailsSafeInsteadOfUsingPrototypeAmounts() {
        when(config.activeValue("K.rewards.welcomeGift.usdtAmount")).thenReturn(Optional.empty());
        when(config.activeValue("K.rewards.welcomeGift.nexAmount")).thenReturn(Optional.empty());
        when(config.activeValue("K.rewards.inviterReward.nexAmount")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.runSettlements("idem-ref-no-config",
                request(10, "manual reconciliation")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("REFERRAL_REWARD_NOT_CONFIGURED");

        verify(earningsRelease, never()).creditReward(any(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString());
        verify(ledger, never()).postLedgerEntry(anyString(), any(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString());
    }

    @Test
    void overviewAndParamWriteExposeMonotonicConfigurationVersion() {
        Map<String, Object> overview = service.overview();
        assertThat(overview).containsEntry("version", 1L);

        Map<String, Object> updated = service.updateParam("newcomer.usdt", "idem-ref-version",
                new ReferralRewardParamUpdateRequest("newcomer.usdt", "5", 1L,
                        "versioned parameter update", "superadmin"));

        assertThat(updated).containsEntry("version", 2L);
        verify(config).upsertAdminValue("K.rewards.referral.version", "2", "NUMBER",
                "GROWTH_REFERRAL", "H8 referral reward configuration version");
    }

    @Test
    void staleParameterVersionFailsClosedAndWritesRejectedAudit() {
        when(config.activeValueForUpdate("K.rewards.referral.version")).thenReturn(Optional.of("2"));
        assertThatThrownBy(() -> service.updateParam("newcomer.usdt", "idem-ref-stale",
                new ReferralRewardParamUpdateRequest("newcomer.usdt", "4", 1L,
                        "stale parameter update", "superadmin")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("H8_CONFIG_VERSION_CONFLICT");

        verify(config, never()).upsertAdminValue(eq("K.rewards.welcomeGift.usdtAmount"), anyString(),
                anyString(), anyString(), anyString());
        verify(audit).recordRequiredInNewTransaction(argThat(request ->
                "REFERRAL_REWARD_PARAM_UPDATE_REJECTED".equals(request.getAction())
                        && "REJECTED".equals(request.getResult())));
    }

    @Test
    void invalidParameterWritesRejectedAuditInIndependentTransaction() {
        assertThatThrownBy(() -> service.updateParam("newcomer.usdt", "idem-ref-invalid",
                new ReferralRewardParamUpdateRequest("newcomer.usdt", "-1", 1L,
                        "invalid parameter verification", "superadmin")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("REFERRAL_REWARD_AMOUNT_INVALID");

        verify(audit).recordRequiredInNewTransaction(argThat(request ->
                "REFERRAL_REWARD_PARAM_UPDATE_REJECTED".equals(request.getAction())
                        && "REJECTED".equals(request.getResult())));
    }

    @Test
    void staleApprovedRewardSnapshotCannotSettleAfterH8OrH1Changes() {
        ReferralSettlementRunRequest approved = request(10, "approved reward snapshot");
        when(config.activeValue("growth.phase.month.7.inviteRewardMultiplier"))
                .thenReturn(Optional.of("1.5"));

        assertThatThrownBy(() -> service.runSettlements(
                "idem-ref-stale-snapshot", approved))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("H8_REWARD_SNAPSHOT_CHANGED_REPROPOSE");

        verify(mapper, never()).findPendingReferrals(any(LocalDateTime.class), anyString(), anyInt(), anyBoolean(), anyInt(), any());
    }

    @Test
    void effectiveRewardRoundsOnceAndRejectsTheSharedProductCeiling() {
        when(config.activeValue("K.rewards.inviterReward.nexAmount"))
                .thenReturn(Optional.of("249999999.75"));
        when(config.activeValue("growth.phase.month.7.inviteRewardMultiplier"))
                .thenReturn(Optional.of("4.000001"));

        assertThatThrownBy(service::overview)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("REFERRAL_REWARD_EFFECTIVE_AMOUNT_OVERFLOW");
    }

    private ReferralSettlementRunRequest request(int limit, String reason) {
        if (!A2ReplayContext.isReplaying()) {
            return new ReferralSettlementRunRequest(limit, reason, "superadmin", null, null, null);
        }
        Map<String, Object> overview = service.overview();
        return new ReferralSettlementRunRequest(
                limit,
                reason,
                "superadmin",
                ((Number) overview.get("version")).longValue(),
                ((Number) overview.get("rhythmMonth")).intValue(),
                String.valueOf(overview.get("rewardSnapshotHash")));
    }

    private static BigDecimal decimal(String expected) {
        BigDecimal target = new BigDecimal(expected);
        return argThat(actual -> actual != null && actual.compareTo(target) == 0);
    }
}
