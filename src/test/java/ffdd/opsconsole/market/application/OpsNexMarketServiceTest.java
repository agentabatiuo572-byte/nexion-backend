package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import ffdd.opsconsole.emergency.application.OpsKillSwitchService;
import ffdd.opsconsole.market.domain.ExchangeOrderView;
import ffdd.opsconsole.market.domain.GenesisNodeView;
import ffdd.opsconsole.market.domain.GenesisPolicyView;
import ffdd.opsconsole.market.domain.GenesisSecondaryStatsView;
import ffdd.opsconsole.market.domain.GenesisSeriesView;
import ffdd.opsconsole.market.domain.NexMarketRepository;
import ffdd.opsconsole.market.domain.NexPricePointView;
import ffdd.opsconsole.market.domain.RepurchaseAmountBucketView;
import ffdd.opsconsole.market.domain.RepurchaseStatsView;
import ffdd.opsconsole.market.domain.RepurchaseStatusView;
import ffdd.opsconsole.market.domain.StakingPositionView;
import ffdd.opsconsole.market.domain.StakingProductView;
import ffdd.opsconsole.market.dto.ExchangeKycReviewRequest;
import ffdd.opsconsole.market.dto.ExchangeParamUpdateRequest;
import ffdd.opsconsole.market.dto.ExchangeQueueCancelRequest;
import ffdd.opsconsole.market.dto.ExchangeSwapStatusRequest;
import ffdd.opsconsole.market.dto.NexMarketAdvanceRequest;
import ffdd.opsconsole.market.dto.NexMarketCurveFrame;
import ffdd.opsconsole.market.dto.NexMarketCurveUpdateRequest;
import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.risk.facade.KycReviewTriggerResult;
import ffdd.opsconsole.risk.facade.RiskKycReviewFacade;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsNexMarketServiceTest {
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final FakeTreasuryCoverageFacade coverageFacade = new FakeTreasuryCoverageFacade();
    private final FakeNexMarketRepository marketRepository = new FakeNexMarketRepository();
    private final FakeEmergencyControlRepository emergencyRepository = new FakeEmergencyControlRepository();
    private final OpsKillSwitchService killSwitchService = mock(OpsKillSwitchService.class);
    private final FakeTreasuryLedgerPostingFacade ledgerPostingFacade = new FakeTreasuryLedgerPostingFacade();
    private final FakeRiskKycReviewFacade riskKycReviewFacade = new FakeRiskKycReviewFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AdminPermissionCache permissionCache = mock(AdminPermissionCache.class);
    private final AuditObjectLockMapper lockMapper = mock(AuditObjectLockMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-17T00:00:00Z"), ZoneId.of("UTC"));
    private final OpsNexMarketService service = service();

    private OpsNexMarketService service() {
        return service(OpsReadTimeSeedPolicy.enabledForDirectConstruction());
    }

    private OpsNexMarketService service(OpsReadTimeSeedPolicy seedPolicy) {
        OpsNexMarketService service = new OpsNexMarketService(
                configFacade,
                coverageFacade,
                marketRepository,
                emergencyRepository,
                killSwitchService,
                ledgerPostingFacade,
                riskKycReviewFacade,
                auditLogService,
                new ObjectMapper(),
                clock,
                seedPolicy,
                permissionCache,
                lockMapper);
        return service;
    }

    @BeforeEach
    void seedPermissionContext() {
        // update* service 层二次校验需 admin id + 权限码;默认 stub 全 G 域 write 码让校验通过
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
        when(permissionCache.getPermissionCodes(anyLong())).thenReturn(Set.of(
                "finprod_g1_apy_write", "finprod_g1_penalty_write", "finprod_g1_min_write",
                "finprod_g1_write", "finprod_g1_kill_toggle",
                "finprod_g2_cap_user_write", "finprod_g2_cap_platform_write", "finprod_g2_fee_rate_write",
                "finprod_g2_write", "finprod_g2_swap_toggle",
                "finprod_g3_write", "finprod_g3_override_price_write", "finprod_g3_engine_pause_toggle",
                "finprod_g3_curve_target_price_write", "finprod_g3_curve_pump_prob_write",
                "finprod_g4_write", "finprod_g4_price_write", "finprod_g4_dividend_rate_write",
                "finprod_g4_royalty_write", "finprod_g4_market_toggle",
                "finprod_g4_airdrop_pct_write", "finprod_g4_emission_curve_write",
                "finprod_g4_airdrop_lock_days_write",
                "finprod_g7_apy_write", "finprod_g7_nurture_write", "finprod_g7_write"));
        when(killSwitchService.changeFromLinkedDomain(
                anyString(), org.mockito.ArgumentMatchers.anyBoolean(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(ApiResult.ok(Map.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void stubLocksNoActive() {
        // A2 锁守卫默认放行:countActiveByTarget=0 表示无活跃锁,replay 与常规写方法直通
        when(lockMapper.countActiveByTarget(anyString(), anyString(), anyString())).thenReturn(0);
    }

    @Test
    void overviewExposesSevenFrameCurveAndSunsetExclusions() {
        configFacade.values.put("wallet.nex_market.weekly_curve", curveJson("0.171"));
        marketRepository.pricePoints = pricePoints(
                "0.17100000",
                "0.17400000",
                "0.17800000",
                "0.18100000",
                "0.18400000",
                "0.18200000",
                "0.17900000");
        seedSunsetExclusions();

        ApiResult<Map<String, Object>> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat((List<?>) result.getData().get("frames")).hasSize(7);
        assertThat((List<?>) result.getData().get("controls"))
                .extracting("key")
                .containsExactly("schedule", "pin", "loop");
        assertThat(result.getData()).containsKey("overrides");
        assertThat(result.getData().get("sunsetExclusions"))
                .asList()
                .contains("Premium", "NEX v2", "Points");
    }

    @Test
    void overviewDoesNotSeedMissingDbOrConfigBeforeReadingMarketCurve() {
        configFacade.values.clear();
        marketRepository.latestPrice = Optional.empty();
        marketRepository.latestSparkline = Optional.empty();
        marketRepository.pricePoints = List.of();

        assertThatThrownBy(service::overview)
                .isInstanceOf(BizException.class)
                .hasMessage("G3_WEEKLY_CURVE_INVALID");
        assertThat(marketRepository.marketSeeded).isFalse();
        assertThat(configFacade.values).isEmpty();
    }

    @Test
    void curveHistoryDoesNotSeedMissingDbBeforeReadingSparkline() {
        configFacade.values.clear();
        marketRepository.latestPrice = Optional.empty();
        marketRepository.latestSparkline = Optional.empty();
        marketRepository.pricePoints = List.of();

        ApiResult<Map<String, Object>> result = service.curveHistory();

        assertThat(result.getCode()).isZero();
        assertThat(marketRepository.marketSeeded).isFalse();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) result.getData().get("points");
        assertThat(points).isEmpty();
    }

    @Test
    void exchangeOverviewUsesServerOrdersConfigAndJ1Switch() {
        marketRepository.orders = List.of(exchange("EX-Q-1", "QUEUED"), exchange("EX-KYC-1", "KYC_REQUIRED"));
        configFacade.values.put("wallet.exchange.platform_daily_cap_usdt", "20000");
        emergencyRepository.settings.put("emergency.killswitch.exchange", "off");
        emergencyRepository.upsertGeoCountryPolicy("US", "美国", "blocked", "", "test");

        ApiResult<Map<String, Object>> result = service.exchangeOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("domain", "G2");
        assertThat((List<?>) result.getData().get("queue")).hasSize(1);
        assertThat(detailMap(result.getData().get("swap"))).containsEntry("enabled", false);
        assertThat((List<?>) result.getData().get("geoBlocked")).isNotEmpty();
    }

    @Test
    void missingJ1RowsKeepTheRealG1G2G4SurfacesEnabledLikeTheJ1DisplayDefault() {
        emergencyRepository.settings.clear();

        ApiResult<Map<String, Object>> staking = service.stakingOverview();
        ApiResult<Map<String, Object>> exchange = service.exchangeOverview();
        ApiResult<Map<String, Object>> genesis = service.genesisOverview();

        assertThat(detailMap(staking.getData().get("gate"))).containsEntry("enabled", true);
        assertThat(detailMap(exchange.getData().get("swap"))).containsEntry("enabled", true);
        assertThat(detailMap(genesis.getData().get("market"))).containsEntry("enabled", true);
    }

    @Test
    void exchangeOverviewReadsI4DisclosureGateAsBlocked() {
        emergencyRepository.settings.put("disclosure.gate.exchange", "true");

        ApiResult<Map<String, Object>> result = service.exchangeOverview();

        assertThat(result.getCode()).isZero();
        assertThat(detailMap(result.getData().get("swap")))
                .containsEntry("enabled", false)
                .containsEntry("blockedBy", "I5_DISCLOSURE_GATE");
        assertThat(detailMap(result.getData().get("disclosureGate")))
                .containsEntry("exchange", true);
    }

    @Test
    void exchangeOverviewDoesNotSeedMissingDbBeforeReadingOrdersAndGates() {
        marketRepository.orders = List.of();

        ApiResult<Map<String, Object>> result = service.exchangeOverview();

        assertThat(result.getCode()).isZero();
        assertThat(marketRepository.exchangeSeeded).isFalse();
        assertThat((List<?>) result.getData().get("queue"))
                .isEmpty();
        assertThat(detailMap(result.getData().get("stats")))
                .containsEntry("queueDepth", 0L)
                .containsEntry("gateKyc", 0L)
                .containsEntry("gateUser", 0L)
                .containsEntry("gatePlatform", 0L);
    }

    @Test
    void exchangeOverviewDoesNotSeedWhenExistingOrdersDoNotProvideQueueOrGates() {
        marketRepository.orders = List.of(exchange("EX-DONE-1", "COMPLETED"));

        ApiResult<Map<String, Object>> result = service.exchangeOverview();

        assertThat(result.getCode()).isZero();
        assertThat(marketRepository.exchangeSeeded).isFalse();
        assertThat((List<?>) result.getData().get("queue"))
                .isEmpty();
    }

    @Test
    void disabledReadTimeSeedsDoNotExposeG3ScheduleOrSeedExchangeQueue() {
        configFacade.values.clear();
        marketRepository.orders = List.of();
        OpsNexMarketService realOnlyService = service(OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        assertThatThrownBy(realOnlyService::overview)
                .isInstanceOf(BizException.class)
                .hasMessage("G3_WEEKLY_CURVE_INVALID");
        ApiResult<Map<String, Object>> exchange = realOnlyService.exchangeOverview();

        assertThat(realOnlyService.currentSchedule().displayValue()).isEmpty();
        assertThat(realOnlyService.currentSchedule().cronExpression()).isEmpty();
        assertThat(exchange.getCode()).isZero();
        assertThat(marketRepository.exchangeSeeded).isFalse();
        assertThat((List<?>) exchange.getData().get("queue")).isEmpty();
        assertThat(detailMap(exchange.getData().get("stats"))).containsEntry("queueDepth", 0L);
    }

    @Test
    void disabledReadTimeSeedsDoNotBackfillGeoBlockReason() {
        configFacade.values.clear();
        emergencyRepository.upsertGeoCountryPolicy("US", "美国", "blocked", "", "test");
        OpsNexMarketService realOnlyService = service(OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        ApiResult<Map<String, Object>> result = realOnlyService.exchangeOverview();

        assertThat(result.getCode()).isZero();
        assertThat((List<Map<String, Object>>) result.getData().get("geoBlocked"))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("cc", "US")
                        .containsEntry("status", "blocked")
                        .containsEntry("reason", ""));
    }

    @Test
    void looseningExchangeCapBelowB1RedlineReturns422() {
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));
        configFacade.values.put("wallet.exchange.user_daily_cap_usdt", "50");

        ApiResult<Map<String, Object>> result = service.updateExchangeParam(
                "idem-g2-cap",
                "userDailyCap",
                new ExchangeParamUpdateRequest("100", "raise exchange cap", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(configFacade.values).containsEntry("wallet.exchange.user_daily_cap_usdt", "50");
    }

    @Test
    void tighteningQueueModeWritesConfigAndAudits() {
        ApiResult<Map<String, Object>> result = service.updateExchangeParam(
                "idem-g2-queue",
                "queueMode",
                new ExchangeParamUpdateRequest("REJECT", "tighten exchange queue", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("wallet.exchange.queue_mode", "REJECT");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("G2_EXCHANGE_PARAM_CHANGED");
    }

    @Test
    void restoringExchangeSwapBelowRedlineReturns422() {
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));
        emergencyRepository.settings.put("killswitch.exchange", "disabled");

        ApiResult<Map<String, Object>> result = service.updateExchangeSwapStatus(
                "idem-g2-swap",
                new ExchangeSwapStatusRequest(true, "restore exchange", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(emergencyRepository.settings).containsEntry("killswitch.exchange", "disabled");
    }

    @Test
    void cancelQueuedExchangeUpdatesOrderAndAudits() {
        marketRepository.orders = List.of(exchange("EX-Q-1", "QUEUED"));

        ApiResult<Map<String, Object>> result = service.cancelExchangeQueueOrder(
                "idem-cancel",
                "EX-Q-1",
                new ExchangeQueueCancelRequest("geo blocked", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(marketRepository.cancelled).containsExactly("EX-Q-1");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("G2_EXCHANGE_QUEUE_ORDER_CANCELLED");
    }

    @Test
    void cancelNonQueuedExchangeReturns409() {
        marketRepository.orders = List.of(exchange("EX-DONE-1", "COMPLETED"));

        ApiResult<Map<String, Object>> result = service.cancelExchangeQueueOrder(
                "idem-cancel",
                "EX-DONE-1",
                new ExchangeQueueCancelRequest("not queued", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void triggerLargeExchangeKycReviewCallsK5AndMovesOrderToKycRequired() {
        marketRepository.orders = List.of(exchange("EX-LARGE-1", "QUEUED", new BigDecimal("8200.00")));

        ApiResult<Map<String, Object>> result = service.triggerExchangeKycReview(
                "idem-g2-k5",
                "EX-LARGE-1",
                new ExchangeKycReviewRequest("large exchange review", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(riskKycReviewFacade.lastExchangeNo).isEqualTo("EX-LARGE-1");
        assertThat(riskKycReviewFacade.lastAmountUsdt).isEqualByComparingTo("8200.00");
        assertThat(marketRepository.findExchangeOrder("EX-LARGE-1"))
                .get()
                .extracting(ExchangeOrderView::status)
                .isEqualTo("KYC_REQUIRED");
        assertThat(detailMap(result.getData().get("updated")))
                .containsEntry("before", "QUEUED")
                .containsEntry("after", "KYC_REQUIRED")
                .containsEntry("ticketId", "KR-G2-TEST");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("G2_EXCHANGE_K5_REVIEW_REQUIRED");
        assertThat(captor.getValue().getResult()).isEqualTo("BLOCKED");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("idempotencyKey", "idem-g2-k5")
                .containsEntry("toStatus", "KYC_REQUIRED");
    }

    @Test
    void triggerLargeExchangeKycReviewUsesAuthenticatedActorInsteadOfBodyOperator() {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "authenticated-market-admin", "n/a", List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        marketRepository.orders = List.of(exchange("EX-ACTOR-1", "QUEUED", new BigDecimal("8200.00")));

        service.triggerExchangeKycReview(
                "idem-g2-k5-actor", "EX-ACTOR-1",
                new ExchangeKycReviewRequest("large exchange actor boundary", "forged-body-operator"));

        assertThat(riskKycReviewFacade.lastOperator).isEqualTo("admin:authenticated-market-admin");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getActorUsername()).isEqualTo("admin:authenticated-market-admin");
    }

    @Test
    void triggerExchangeKycReviewDoesNotHoldOrderWhenK5TicketAlreadyOpen() {
        marketRepository.orders = List.of(exchange("EX-LARGE-1", "QUEUED", new BigDecimal("8200.00")));
        riskKycReviewFacade.alreadyOpen = true;

        ApiResult<Map<String, Object>> result = service.triggerExchangeKycReview(
                "idem-g2-k5",
                "EX-LARGE-1",
                new ExchangeKycReviewRequest("large exchange review", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("EXCHANGE_K5_REVIEW_ALREADY_OPEN");
        assertThat(marketRepository.findExchangeOrder("EX-LARGE-1"))
                .get()
                .extracting(ExchangeOrderView::status)
                .isEqualTo("QUEUED");
    }

    @Test
    void triggerExchangeKycReviewBlocksWhenI4DisclosureGateActive() {
        emergencyRepository.settings.put("disclosure.gate.exchange", "true");
        marketRepository.orders = List.of(exchange("EX-GATE-1", "QUEUED", new BigDecimal("8200.00")));

        ApiResult<Map<String, Object>> result = service.triggerExchangeKycReview(
                "idem-g2-i4",
                "EX-GATE-1",
                new ExchangeKycReviewRequest("large exchange review", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("G2_DISCLOSURE_GATE_REACK_REQUIRED");
        assertThat(riskKycReviewFacade.lastExchangeNo).isNull();
        assertThat(marketRepository.findExchangeOrder("EX-GATE-1"))
                .get()
                .extracting(ExchangeOrderView::status)
                .isEqualTo("QUEUED");
    }

    @Test
    void triggerExchangeKycReviewBlocksWhenJ2EmergencyGeoBlockActive() {
        emergencyRepository.settings.put("emergency.geo.j4.block.required", "true");
        marketRepository.orders = List.of(exchange("EX-GEO-1", "QUEUED", new BigDecimal("8200.00")));

        ApiResult<Map<String, Object>> result = service.triggerExchangeKycReview(
                "idem-g2-j2",
                "EX-GEO-1",
                new ExchangeKycReviewRequest("large exchange review", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("G2_GEO_BLOCKED");
        assertThat(riskKycReviewFacade.lastExchangeNo).isNull();
        assertThat(marketRepository.findExchangeOrder("EX-GEO-1"))
                .get()
                .extracting(ExchangeOrderView::status)
                .isEqualTo("QUEUED");
    }

    @Test
    void triggerExchangeKycReviewRollsBackWhenOrderLeavesQueueBeforeHold() {
        marketRepository.orders = List.of(exchange("EX-LARGE-1", "QUEUED", new BigDecimal("8200.00")));
        marketRepository.failConditionalUpdate = true;

        assertThatThrownBy(() -> service.triggerExchangeKycReview(
                "idem-g2-k5",
                "EX-LARGE-1",
                new ExchangeKycReviewRequest("large exchange review", "superadmin")))
                .isInstanceOf(BizException.class)
                .hasMessage(OpsErrorCode.INVALID_STATE_TRANSITION.name());
        assertThat(marketRepository.findExchangeOrder("EX-LARGE-1"))
                .get()
                .extracting(ExchangeOrderView::status)
                .isEqualTo("QUEUED");
    }

    @Test
    void triggerExchangeKycReviewRejectsTerminalOrder() {
        marketRepository.orders = List.of(exchange("EX-DONE-1", "COMPLETED", new BigDecimal("8200.00")));

        ApiResult<Map<String, Object>> result = service.triggerExchangeKycReview(
                "idem-g2-k5",
                "EX-DONE-1",
                new ExchangeKycReviewRequest("large exchange review", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(riskKycReviewFacade.lastExchangeNo).isNull();
    }

    @Test
    void triggerExchangeKycReviewRejectsNonQueueGateOrder() {
        marketRepository.orders = List.of(exchange("EX-CAP-1", "USER_CAP", new BigDecimal("8200.00")));

        ApiResult<Map<String, Object>> result = service.triggerExchangeKycReview(
                "idem-g2-k5",
                "EX-CAP-1",
                new ExchangeKycReviewRequest("large exchange review", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(riskKycReviewFacade.lastExchangeNo).isNull();
    }

    @Test
    void raisingCurveBelowB1CoverageRedlineReturns422() {
        configFacade.values.put("wallet.nex_market.weekly_curve", curveJson("0.171"));
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));

        ApiResult<Map<String, Object>> result = service.updateWeeklyCurve(
                "idem-g3",
                new NexMarketCurveUpdateRequest(frames("0.200"), "raise price", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
    }

    @Test
    void validCurveWritesConfigPublishesActiveFrameAndAudits() {
        configFacade.values.put("wallet.nex_market.weekly_curve", curveJson("0.171"));
        ApiResult<Map<String, Object>> result = service.updateWeeklyCurve(
                "idem-g3",
                new NexMarketCurveUpdateRequest(frames("0.160"), "weekly schedule", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsKey("wallet.nex_market.weekly_curve");
        assertThat(configFacade.values).containsEntry("wallet.exchange.nex_usdt_price", "0.16");
        assertThat(marketRepository.lastPrice).isEqualByComparingTo("0.16000000");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("G3_WEEKLY_CURVE_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-g3");
    }

    @Test
    void advanceRequiresIdempotencyKey() {
        ApiResult<Map<String, Object>> result = service.advanceCurrentFrame(
                null,
                new NexMarketAdvanceRequest("daily frame", "system"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    @Test
    void advancePersistsActiveDayAndFeedsDownstreamMarketSurfaces() {
        configFacade.values.put("wallet.nex_market.weekly_curve", steppedCurveJson());
        configFacade.values.put("wallet.nex_market.control.active_day_index", "2");
        marketRepository.pricePoints = pricePoints(
                "0.10",
                "0.11",
                "0.12",
                "0.13",
                "0.14",
                "0.15");

        ApiResult<Map<String, Object>> result = service.advanceCurrentFrame(
                "idem-g3-advance",
                new NexMarketAdvanceRequest("manual next frame", "system"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("activeDayIndex", 3);
        assertThat(configFacade.values)
                .containsEntry("wallet.nex_market.control.active_day_index", "3")
                .containsEntry("wallet.exchange.nex_usdt_price", "0.13");
        NexMarketCurveFrame activeFrame = (NexMarketCurveFrame) result.getData().get("activeFrame");
        assertThat(activeFrame.targetPrice()).isEqualByComparingTo("0.13");
        assertThat((BigDecimal) service.exchangeOverview().getData().get("currentPrice")).isEqualByComparingTo("0.13");
        assertThat((BigDecimal) service.stakingOverview().getData().get("currentNexPrice")).isEqualByComparingTo("0.13");
        assertThat((BigDecimal) service.genesisOverview().getData().get("currentNexPrice")).isEqualByComparingTo("0.13");
        assertThat((BigDecimal) service.repurchaseOverview().getData().get("currentNexPrice")).isEqualByComparingTo("0.13");
    }

    @Test
    void updateControlWritesConfigAndAudits() {
        configFacade.values.put("wallet.nex_market.weekly_curve", curveJson("0.171"));
        ApiResult<Map<String, Object>> result = service.updateControl(
                "idem-control",
                "pin",
                new NexMarketValueUpdateRequest("D3", "pin demo day", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("wallet.nex_market.control.pin", "D3");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("G3_CONTROL_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-control");
    }

    @Test
    void updateScheduleControlNormalizesTimeAndWritesConfig() {
        configFacade.values.put("wallet.nex_market.weekly_curve", curveJson("0.171"));
        ApiResult<Map<String, Object>> result = service.updateControl(
                "idem-schedule",
                "schedule",
                new NexMarketValueUpdateRequest("每日 08:30 Asia/Shanghai 自动推进", "change daily advance time", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("wallet.nex_market.control.schedule", "每日 08:30 Asia/Shanghai 自动推进");
    }

    @Test
    void updateScheduleControlRejectsInvalidTime() {
        configFacade.values.put("wallet.nex_market.control.schedule", "每日 00:00 UTC 自动推进");

        ApiResult<Map<String, Object>> result = service.updateControl(
                "idem-schedule-invalid",
                "schedule",
                new NexMarketValueUpdateRequest("每天凌晨", "bad schedule", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("G3_SCHEDULE_INVALID");
        assertThat(configFacade.values)
                .containsEntry("wallet.nex_market.control.schedule", "每日 00:00 UTC 自动推进");
    }

    @Test
    void scheduledAdvanceSkipsWhenEnginePaused() {
        configFacade.values.put("wallet.nex_market.paused", "true");

        service.advanceScheduledFrame();

        assertThat(marketRepository.lastPrice).isNull();
    }

    @Test
    void scheduledAdvanceSkipsWhenPinned() {
        configFacade.values.put("wallet.nex_market.control.pin", "D3");

        service.advanceScheduledFrame();

        assertThat(marketRepository.lastPrice).isNull();
    }

    @Test
    void updateOverridePricePublishesMarketPriceAndAudits() {
        configFacade.values.put("wallet.nex_market.weekly_curve", curveJson("0.171"));
        ApiResult<Map<String, Object>> result = service.updateOverride(
                "idem-price",
                "currentPrice",
                new NexMarketValueUpdateRequest("0.166", "temporary market override", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("wallet.exchange.nex_usdt_price", "0.166");
        assertThat(marketRepository.lastPrice).isEqualByComparingTo("0.16600000");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("G3_OVERRIDE_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("overrideKey", "currentPrice");
    }

    @Test
    void unsupportedOverrideReturnsValidationFailure() {
        ApiResult<Map<String, Object>> result = service.updateOverride(
                "idem-invalid",
                "premiumGate",
                new NexMarketValueUpdateRequest("enabled", "old feature", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("G3_OVERRIDE_KEY_INVALID");
    }

    @Test
    void repurchaseOverviewExposesServerCanonicalConfigAndB1Coverage() {
        seedSunsetExclusions();

        ApiResult<Map<String, Object>> result = service.repurchaseOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("domain", "G7");
        assertThat((List<?>) result.getData().get("params"))
                .extracting("key")
                .contains("apy", "nurture", "lottery", "penalty", "presets");
        assertThat(detailMap(result.getData().get("coverage")))
                .containsEntry("redlineBreached", false);
        assertThat(result.getData().get("sunsetExclusions"))
                .asList()
                .contains("Premium", "NEX v2", "Points");
    }

    @Test
    void repurchaseOverviewDoesNotSeedMissingDbBeforeReadingPositionsAndBuckets() {
        marketRepository.repurchasePositions = List.of();

        ApiResult<Map<String, Object>> result = service.repurchaseOverview();

        assertThat(result.getCode()).isZero();
        assertThat(marketRepository.repurchaseSeeded).isFalse();
        assertThat(detailMap(result.getData().get("stats")))
                .containsEntry("ordersMonth", 0L)
                .containsEntry("principalUsd", BigDecimal.ZERO);
        assertThat(result.getData().get("amountDistribution"))
                .isEqualTo("-");
        assertThat((List<?>) result.getData().get("statusBreakdown"))
                .isEmpty();
        assertThat((List<?>) result.getData().get("sources"))
                .asList()
                .contains("nx_staking_product:repurchase", "nx_staking_position:repurchase");
    }

    @Test
    void raisingRepurchaseApyBelowCoverageRedlineReturns422() {
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));

        ApiResult<Map<String, Object>> result = service.updateRepurchaseParam(
                "idem-g7-apy",
                "apy",
                new NexMarketValueUpdateRequest("40", "raise repurchase apy", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(marketRepository.repurchaseProduct().orElseThrow().apyBps()).isEqualByComparingTo("3500");
    }

    @Test
    void updatingRepurchasePresetWritesConfigAndAudits() {
        ApiResult<Map<String, Object>> result = service.updateRepurchaseParam(
                "idem-g7-preset",
                "presets",
                new NexMarketValueUpdateRequest("$100 / 300 / 500", "campaign presets", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(marketRepository.repurchaseProduct().orElseThrow().presetAmounts()).isEqualTo("$100 / 300 / 500");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("G7_REPURCHASE_PARAM_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-g7-preset");
    }

    @Test
    void genesisOverviewExposesServerCanonicalNodeLedgerAndJ1Switch() {
        emergencyRepository.settings.put("J.killswitch.genesis", "off");
        seedSunsetExclusions();

        ApiResult<Map<String, Object>> result = service.genesisOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("domain", "G4");
        assertThat(detailMap(result.getData().get("market"))).containsEntry("enabled", false);
        assertThat((List<?>) result.getData().get("params"))
                .extracting("key")
                .contains("supply", "price", "dividend", "royalty", "divBase", "airdropPct", "emissionCurve", "airdropLockDays");
        assertThat((List<?>) result.getData().get("nodes"))
                .extracting("id")
                .contains("#0042", "#0117", "#0233");
        assertThat((List<?>) result.getData().get("sources"))
                .asList()
                .contains("nx_genesis_series", "nx_genesis_holding", "nx_genesis_order");
        assertThat(result.getData().get("sunsetExclusions"))
                .asList()
                .contains("Premium", "NEX v2", "Points");
    }

    @Test
    void genesisOverviewSuppressesLiveEmissionAmountsWhileH1GateIsClosed() {
        configFacade.values.put("growth.phase.genesis_emissions_open", "false");

        ApiResult<Map<String, Object>> result = service.genesisOverview();

        assertThat(detailMap(result.getData().get("emissionGate"))).containsEntry("open", false);
        assertThat(detailMap(result.getData().get("dividend")))
                .containsEntry("poolToday", new BigDecimal("0.00"))
                .containsEntry("payoutToday", new BigDecimal("0.00"));
        assertThat(detailMap(result.getData().get("stats")))
                .containsEntry("genesisAccrualUsd", new BigDecimal("0.00"));
    }

    @Test
    void genesisOverviewReadsJ1CanonicalKillSwitchKey() {
        emergencyRepository.settings.put("killswitch.genesis", "disabled");

        ApiResult<Map<String, Object>> result = service.genesisOverview();

        assertThat(result.getCode()).isZero();
        assertThat(detailMap(result.getData().get("market")))
                .containsEntry("enabled", false)
                .containsEntry("configKey", "killswitch.genesis");
    }

    @Test
    void genesisOverviewReadsI4DisclosureGateAsBlocked() {
        emergencyRepository.settings.put("disclosure.gate.genesis", "true");

        ApiResult<Map<String, Object>> result = service.genesisOverview();

        assertThat(result.getCode()).isZero();
        assertThat(detailMap(result.getData().get("market")))
                .containsEntry("enabled", false)
                .containsEntry("blockedBy", "I5_DISCLOSURE_GATE");
        assertThat(detailMap(result.getData().get("disclosureGate")))
                .containsEntry("genesis", true);
    }

    @Test
    void genesisNodeLedgerIsPaginated() {
        ApiResult<Map<String, Object>> firstPage = service.genesisOverview(1, 2);

        assertThat(firstPage.getCode()).isZero();
        assertThat((List<?>) firstPage.getData().get("nodes"))
                .extracting("id")
                .containsExactly("#0042", "#0117");
        assertThat(detailMap(firstPage.getData().get("nodePage")))
                .containsEntry("page", 1)
                .containsEntry("pageSize", 2)
                .containsEntry("total", 3L)
                .containsEntry("totalPages", 2)
                .containsEntry("hasNext", true);

        ApiResult<Map<String, Object>> secondPage = service.genesisOverview(2, 2);

        assertThat(secondPage.getCode()).isZero();
        assertThat((List<?>) secondPage.getData().get("nodes"))
                .extracting("id")
                .containsExactly("#0233");
        assertThat(detailMap(secondPage.getData().get("nodePage")))
                .containsEntry("page", 2)
                .containsEntry("hasPrev", true)
                .containsEntry("hasNext", false);
    }

    @Test
    void genesisOverviewDoesNotSeedMissingDbBeforeReadingSeriesAndNodeLedger() {
        marketRepository.genesisSeries = Optional.empty();
        marketRepository.genesisPolicy = Optional.empty();
        marketRepository.genesisNodes = List.of();
        marketRepository.genesisSecondaryStats = new GenesisSecondaryStatsView(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0L,
                0L);

        ApiResult<Map<String, Object>> result = service.genesisOverview();

        assertThat(result.getCode()).isZero();
        assertThat(marketRepository.genesisSeeded).isFalse();
        assertThat(detailMap(result.getData().get("stats")))
                .containsEntry("totalSlots", BigDecimal.ZERO)
                .containsEntry("sold", 0);
        assertThat((List<?>) result.getData().get("nodes"))
                .isEmpty();
    }

    @Test
    void raisingGenesisDividendBelowCoverageRedlineReturns422() {
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));

        ApiResult<Map<String, Object>> result = service.updateGenesisParam(
                "idem-g4-dividend",
                "dividend",
                new NexMarketValueUpdateRequest("0.2", "raise genesis dividend", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(marketRepository.activeGenesisPolicy().orElseThrow().dailyDividendRatePct()).isEqualByComparingTo("0.1");
    }

    @Test
    void updatingGenesisRoyaltyWritesConfigAndAudits() {
        ApiResult<Map<String, Object>> result = service.updateGenesisParam(
                "idem-g4-royalty",
                "royalty",
                new NexMarketValueUpdateRequest("3.0", "secondary market policy", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(marketRepository.activeGenesisPolicy().orElseThrow().royaltyPct()).isEqualByComparingTo("3");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("G4_GENESIS_PARAM_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-g4-royalty");
    }

    @Test
    void updatingGenesisEmissionPolicyPersistsPlatformConfig() {
        ApiResult<Map<String, Object>> result = service.updateGenesisParam(
                "idem-g4-emission",
                "airdropPct",
                new NexMarketValueUpdateRequest("9", "adjust protocol emission allocation", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("G.genesis.airdropPct", "9");
        assertThat((List<?>) result.getData().get("params"))
                .anySatisfy(item -> assertThat((Map<String, Object>) item)
                        .containsEntry("key", "airdropPct")
                        .containsEntry("displayValue", "9%"));
    }

    @Test
    void rerunGenesisDividendBatchWritesMarkerAndAudits() {
        configFacade.values.put("growth.phase.genesis_emissions_open", "true");
        ApiResult<Map<String, Object>> result = service.rerunGenesisDividendBatch(
                "idem-g4-rerun",
                "GD-0611",
                new NexMarketValueUpdateRequest("rerun", "retry failed payout rows", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values.keySet()).noneMatch(key -> key.contains("rerun"));
        assertThat(ledgerPostingFacade.entries).hasSize(1);
        assertThat(ledgerPostingFacade.entries.get(0))
                .containsEntry("bizNo", "G4-DIVIDEND-GD-0611-RERUN")
                .containsEntry("userId", 0L)
                .containsEntry("bizType", "GENESIS_DIVIDEND")
                .containsEntry("asset", "USDT")
                .containsEntry("direction", "IN")
                .containsEntry("status", "PENDING");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("G4_GENESIS_DIVIDEND_BATCH_RERUN");
    }

    @Test
    void rerunGenesisDividendBatchRejectsWhenNoPositiveLedgerAmountExists() {
        configFacade.values.put("growth.phase.genesis_emissions_open", "true");
        OpsNexMarketService realOnlyService = service(OpsReadTimeSeedPolicy.disabledForDirectConstruction());
        marketRepository.genesisSecondaryStats = new GenesisSecondaryStatsView(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0L,
                0L);

        ApiResult<Map<String, Object>> result = realOnlyService.rerunGenesisDividendBatch(
                "idem-g4-rerun-empty",
                "GD-EMPTY",
                new NexMarketValueUpdateRequest("rerun", "retry failed payout rows", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("G4_GENESIS_DIVIDEND_RERUN_AMOUNT_UNAVAILABLE");
        assertThat(configFacade.values.keySet()).noneMatch(key -> key.contains("GD-EMPTY"));
        assertThat(ledgerPostingFacade.entries).isEmpty();
    }

    @Test
    void stakingOverviewExposesServerCanonicalPoolsPositionsAndJ1Switch() {
        emergencyRepository.settings.put("J.killswitch.staking", "off");
        configFacade.values.put("G.staking.usdt180d.killed", "true");
        seedSunsetExclusions();

        ApiResult<Map<String, Object>> result = service.stakingOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("domain", "G1");
        assertThat(detailMap(result.getData().get("stats"))).containsEntry("stakingGateOn", false);
        assertThat((List<?>) result.getData().get("pools"))
                .extracting("tierKey")
                .containsExactly("usdt30d", "usdt90d", "usdt180d", "usdt365d");
        assertThat((List<?>) result.getData().get("positions"))
                .extracting("status")
                .contains("pending_lock", "active", "mature_unclaimed", "early_withdrawn");
        assertThat(result.getData().get("sunsetExclusions"))
                .asList()
                .contains("Premium", "NEX v2", "Points");
    }

    @Test
    void stakingOverviewReadsJ1CanonicalKillSwitchKey() {
        emergencyRepository.settings.put("killswitch.staking", "disabled");

        ApiResult<Map<String, Object>> result = service.stakingOverview();

        assertThat(result.getCode()).isZero();
        assertThat(detailMap(result.getData().get("gate")))
                .containsEntry("enabled", false)
                .containsEntry("configKey", "killswitch.staking");
    }

    @Test
    void stakingOverviewShowsI5DisclosureAsPerUserRequirementWithoutStoppingG1() {
        configFacade.values.put("disclosure.gate.staking", "true");

        ApiResult<Map<String, Object>> result = service.stakingOverview();

        assertThat(result.getCode()).isZero();
        assertThat(detailMap(result.getData().get("stats"))).containsEntry("stakingGateOn", true);
        assertThat(detailMap(result.getData().get("gate")))
                .containsEntry("enabled", true)
                .doesNotContainKey("blockedBy");
        assertThat(detailMap(result.getData().get("disclosureGate")))
                .containsEntry("staking", true);
    }

    @Test
    void stakingDisclosureUsesConfigTruthBeforeLegacyEmergencyFallback() {
        configFacade.values.put("disclosure.gate.staking", "false");
        emergencyRepository.settings.put("disclosure.gate.staking", "true");

        ApiResult<Map<String, Object>> result = service.stakingOverview();

        assertThat(result.getCode()).isZero();
        assertThat(detailMap(result.getData().get("disclosureGate")))
                .containsEntry("staking", false);
        assertThat(detailMap(result.getData().get("gate")))
                .containsEntry("enabled", true)
                .doesNotContainKey("blockedBy");
    }

    @Test
    void stakingOverviewDoesNotSeedMissingDbBeforeReadingPoolsAndPositions() {
        marketRepository.stakingProducts = List.of();
        marketRepository.stakingPositions = List.of();

        ApiResult<Map<String, Object>> result = service.stakingOverview();

        assertThat(result.getCode()).isZero();
        assertThat(marketRepository.stakingSeeded).isFalse();
        assertThat((List<?>) result.getData().get("pools"))
                .isEmpty();
        assertThat(detailMap(result.getData().get("stats")))
                .containsEntry("activeCount", 0L)
                .containsEntry("matureCount", 0L)
                .containsEntry("pendingCount", 0L);
        assertThat((List<?>) result.getData().get("positions"))
                .extracting("rows")
                .allSatisfy(rows -> assertThat((List<?>) rows).isEmpty());
    }

    @Test
    void raisingStakingApyBelowCoverageRedlineReturns422() {
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));
        configFacade.values.put("G.staking.apy.usdt90d", "35");

        ApiResult<Map<String, Object>> result = service.updateStakingPoolParam(
                "idem-g1-apy",
                "usdt90d",
                "apy",
                new NexMarketValueUpdateRequest("40", "raise staking apy", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(configFacade.values).containsEntry("G.staking.apy.usdt90d", "35");
    }

    @Test
    void loweringLongTermStakingApyBelowShortTermReturns422() {
        ApiResult<Map<String, Object>> result = service.updateStakingPoolParam(
                "idem-g1-order",
                "usdt180d",
                "apy",
                new NexMarketValueUpdateRequest("10", "test ordered tiers", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("G1_STAKING_APY_ORDER_INVALID");
    }

    @Test
    void updatingStakingPenaltyWritesConfigAndAudits() {
        ApiResult<Map<String, Object>> result = service.updateStakingPoolParam(
                "idem-g1-penalty",
                "usdt30d",
                "penalty",
                new NexMarketValueUpdateRequest("6", "tighten early withdraw penalty", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("G.staking.penalty.usdt30d", "6");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("G1_STAKING_POOL_PARAM_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-g1-penalty");
    }

    @Test
    void restoringStakingSaleBelowRedlineReturns422() {
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));
        configFacade.values.put("G.staking.enabled.usdt365d", "false");

        ApiResult<Map<String, Object>> result = service.updateStakingPoolSaleStatus(
                "idem-g1-sale",
                "usdt365d",
                new NexMarketValueUpdateRequest("true", "restore high yield sale", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(configFacade.values).containsEntry("G.staking.enabled.usdt365d", "false");
    }

    @Test
    void globalJ1StakingGateBlocksOpeningAPoolSale() {
        emergencyRepository.settings.put("killswitch.staking", "disabled");
        configFacade.values.put("G.staking.enabled.usdt365d", "false");

        ApiResult<Map<String, Object>> result = service.updateStakingPoolSaleStatus(
                "idem-g1-global-gate",
                "usdt365d",
                new NexMarketValueUpdateRequest("true", "J1 关闭时不得重开质押销售", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("G1_STAKING_GATE_DISABLED");
        assertThat(configFacade.values).containsEntry("G.staking.enabled.usdt365d", "false");
    }

    @Test
    void perUserDisclosureRequirementDoesNotBlockRestoringAPoolSale() {
        configFacade.values.put("disclosure.gate.staking", "true");
        configFacade.values.put("G.staking.enabled.usdt365d", "false");

        ApiResult<Map<String, Object>> result = service.updateStakingPoolSaleStatus(
                "idem-g1-disclosure-is-per-user",
                "usdt365d",
                new NexMarketValueUpdateRequest("true", "restore sale while preserving per-user disclosure", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("G.staking.enabled.usdt365d", "true");
        assertThat(detailMap(result.getData().get("disclosureGate"))).containsEntry("staking", true);
    }

    @Test
    void killingStakingTierWritesConfigAndAudits() {
        ApiResult<Map<String, Object>> result = service.updateStakingPoolKillStatus(
                "idem-g1-kill",
                "usdt365d",
                new NexMarketValueUpdateRequest(
                        "true",
                        "slash open positions by published incident plan",
                        "superadmin",
                        null,
                        "Slash only active positions and preserve all settled balances.",
                        "INC-20260722-G1"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("G.staking.usdt365d.killed", "true");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("G1_STAKING_POOL_KILL_STATUS_CHANGED");
    }

    @Test
    void replayG1StakingPoolParamWritesConfigAndAudits() {
        ApiResult<?> result = service.replay(
                new AuditReplayCommand("G", "g1_staking_pool_param", Map.of(
                        "tierKey", "usdt30d",
                        "paramKey", "penalty",
                        "value", "6")),
                new AuditReplayContext("superadmin", "g1 replay penalty", "idem-replay-g1-penalty"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("G.staking.penalty.usdt30d", "6");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("G1_STAKING_POOL_PARAM_CHANGED");
    }

    @Test
    void replayG3CurveControlWritesConfigAndAudits() {
        configFacade.values.put("wallet.nex_market.weekly_curve", curveJson("0.171"));
        ApiResult<?> result = service.replay(
                new AuditReplayCommand("G", "g3_curve_control", Map.of(
                        "controlKey", "pin",
                        "value", "D3")),
                new AuditReplayContext("superadmin", "g3 replay pin", "idem-replay-g3-pin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("wallet.nex_market.control.pin", "D3");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("G3_CONTROL_CHANGED");
    }

    @Test
    void replayUnknownOpReturns422WithUnknownReplayOpMarker() {
        ApiResult<?> result = service.replay(
                new AuditReplayCommand("G", "g_unknown", Map.of()),
                new AuditReplayContext("superadmin", "replay unknown op", "idem-replay-unknown"));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("UNKNOWN_REPLAY_OP:g_unknown");
    }

    private static List<NexMarketCurveFrame> frames(String price) {
        return java.util.stream.IntStream.range(0, 7)
                .mapToObj(index -> new NexMarketCurveFrame(
                        index,
                        new BigDecimal(price),
                        new BigDecimal("0.08"),
                        new BigDecimal("3")))
                .toList();
    }

    private static String curveJson(String price) {
        return java.util.stream.IntStream.range(0, 7)
                .mapToObj(index -> "{\"dayIndex\":" + index
                        + ",\"targetPrice\":" + price
                        + ",\"pumpProbability\":0.08,\"volatilityPct\":3}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String steppedCurveJson() {
        return java.util.stream.IntStream.range(0, 7)
                .mapToObj(index -> "{\"dayIndex\":" + index
                        + ",\"targetPrice\":" + new BigDecimal("0.10")
                                .add(new BigDecimal("0.01").multiply(BigDecimal.valueOf(index)))
                                .toPlainString()
                        + ",\"pumpProbability\":0.08,\"volatilityPct\":3}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static List<NexPricePointView> pricePoints(String... prices) {
        LocalDateTime sampledAt = LocalDateTime.parse("2026-06-10T00:00:00");
        return java.util.stream.IntStream.range(0, prices.length)
                .mapToObj(index -> new NexPricePointView(
                        new BigDecimal(prices[index]),
                        BigDecimal.ZERO,
                        sampledAt.plusDays(index)))
                .toList();
    }

    private static List<NexMarketCurveFrame> steppedFrames() {
        return java.util.stream.IntStream.range(0, 7)
                .mapToObj(index -> new NexMarketCurveFrame(
                        index,
                        new BigDecimal("0.10").add(new BigDecimal("0.01").multiply(BigDecimal.valueOf(index))),
                        new BigDecimal("0.08"),
                        new BigDecimal("3")))
                .toList();
    }

    private static ExchangeOrderView exchange(String exchangeNo, String status) {
        return exchange(exchangeNo, status, new BigDecimal("17.10"));
    }

    private static ExchangeOrderView exchange(String exchangeNo, String status, BigDecimal amountUsdt) {
        return new ExchangeOrderView(
                1L,
                10001L,
                "U00010001",
                "demo-user",
                "US",
                exchangeNo,
                "NEX",
                "USDT",
                new BigDecimal("100"),
                amountUsdt,
                new BigDecimal("0.171"),
                status,
                status,
                "warn",
                "NEX→USDT",
                amountUsdt,
                null,
                null,
                "明天 00:00",
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    private static ExchangeOrderView exchangeOrderWithStatus(ExchangeOrderView order, String status) {
        return new ExchangeOrderView(
                order.id(),
                order.userId(),
                order.userNo(),
                order.nickname(),
                order.countryCode(),
                order.exchangeNo(),
                order.fromAsset(),
                order.toAsset(),
                order.fromAmount(),
                order.toAmount(),
                order.rate(),
                status,
                status,
                "QUEUED".equals(status) ? "warn" : "CANCELLED".equals(status) ? "dim" : order.statusTone(),
                order.directionLabel(),
                order.amountUsdt(),
                order.gateType(),
                order.gateReason(),
                order.etaLabel(),
                order.createdAt(),
                LocalDateTime.now());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
    }

    private void seedSunsetExclusions() {
        configFacade.values.put("G.sunset.exclusions", "Premium, NEX v2, Points");
    }

    private static final class FakeEmergencyControlRepository implements EmergencyControlRepository {
        private final Map<String, Map<String, Object>> countries = new LinkedHashMap<>();
        private final Map<String, String> settings = new LinkedHashMap<>();

        @Override
        public void ensureTables() {
        }

        @Override
        public List<Map<String, Object>> geoCountryPolicies() {
            return List.copyOf(countries.values());
        }

        @Override
        public void upsertGeoCountryPolicy(String countryCode, String countryName, String status, String reason, String operator) {
            countries.put(countryCode, Map.of(
                    "cc", countryCode,
                    "name", countryName,
                    "status", status,
                    "reason", reason == null ? "" : reason));
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
            return List.of();
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
        public void upsertSetting(
                String settingKey,
                String settingValue,
                String valueType,
                String groupCode,
                String remark,
                String operator) {
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

    private static final class FakePlatformConfigFacade implements PlatformConfigFacade {
        private final Map<String, String> values = new LinkedHashMap<>(Map.of("wallet.exchange.nex_usdt_price", "0.171"));

        @Override
        public Optional<String> activeValue(String configKey) {
            return Optional.ofNullable(values.get(configKey));
        }

        @Override
        public void upsertAdminValue(String configKey, String configValue, String valueType, String configGroup, String remark) {
            values.put(configKey, configValue);
        }
    }

    private static final class FakeTreasuryLedgerPostingFacade implements TreasuryLedgerPostingFacade {
        private final List<Map<String, Object>> entries = new ArrayList<>();

        @Override
        public void postLedgerEntry(String bizNo, Long userId, String bizType, String asset, String direction,
                                    BigDecimal amount, String status, String remark) {
            entries.add(Map.of(
                    "bizNo", bizNo,
                    "userId", userId,
                    "bizType", bizType,
                    "asset", asset,
                    "direction", direction,
                    "amount", amount,
                    "status", status,
                    "remark", remark));
        }
    }

    private static final class FakeTreasuryCoverageFacade implements TreasuryCoverageFacade {
        private TreasuryCoverageSnapshot snapshot = new TreasuryCoverageSnapshot(new BigDecimal("110.00"), new BigDecimal("85.00"));

        @Override
        public TreasuryCoverageSnapshot snapshot() {
            return snapshot;
        }
    }

    private static final class FakeRiskKycReviewFacade implements RiskKycReviewFacade {
        private String lastWithdrawalNo;
        private String lastExchangeNo;
        private BigDecimal lastAmountUsdt;
        private String lastOperator;
        private boolean alreadyOpen;

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
            lastExchangeNo = exchangeNo;
            lastAmountUsdt = amountUsdt;
            lastOperator = operator;
            if (alreadyOpen) {
                return new KycReviewTriggerResult(true, false, null, "K5_REVIEW_ALREADY_OPEN");
            }
            if (amountUsdt != null && amountUsdt.compareTo(new BigDecimal("1000")) >= 0) {
                return new KycReviewTriggerResult(true, true, "KR-G2-TEST", "K5_LARGE_EXCHANGE_REVIEW_REQUIRED");
            }
            return KycReviewTriggerResult.notRequired();
        }
    }

    private static final class FakeNexMarketRepository implements NexMarketRepository {
        private BigDecimal lastPrice;
        private Optional<BigDecimal> latestPrice = Optional.of(new BigDecimal("0.171"));
        private Optional<String> latestSparkline = Optional.empty();
        private List<NexPricePointView> pricePoints = List.of();
        private List<ExchangeOrderView> orders = List.of();
        private List<StakingProductView> stakingProducts = defaultStakingProducts();
        private StakingProductView repurchaseProduct = defaultRepurchaseProduct();
        private List<StakingPositionView> stakingPositions = defaultStakingPositions();
        private List<StakingPositionView> repurchasePositions = defaultRepurchasePositions();
        private Optional<GenesisSeriesView> genesisSeries = Optional.of(defaultGenesisSeries());
        private Optional<GenesisPolicyView> genesisPolicy = Optional.of(defaultGenesisPolicy());
        private GenesisSecondaryStatsView genesisSecondaryStats = defaultGenesisSecondaryStats();
        private List<GenesisNodeView> genesisNodes = defaultGenesisNodes();
        private final java.util.Set<String> genesisDividendReruns = new java.util.LinkedHashSet<>();
        private boolean marketSeeded;
        private boolean exchangeSeeded;
        private boolean stakingSeeded;
        private boolean repurchaseSeeded;
        private boolean genesisSeeded;
        private boolean failConditionalUpdate;
        private final List<String> cancelled = new java.util.ArrayList<>();

        @Override
        public Optional<BigDecimal> latestNexUsdtPrice() {
            return latestPrice;
        }

        @Override
        public Optional<String> latestNexSparkline() {
            return latestSparkline;
        }

        @Override
        public List<NexPricePointView> latestNexPricePoints(int limit) {
            return pricePoints.stream()
                    .sorted(Comparator.comparing(NexPricePointView::sampledAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public void publishNexUsdtPrice(BigDecimal priceUsdt, BigDecimal deltaPercent, String sparklineJson, LocalDateTime sampledAt) {
            lastPrice = priceUsdt;
            latestPrice = Optional.of(priceUsdt);
            latestSparkline = Optional.ofNullable(sparklineJson);
            List<NexPricePointView> rows = new ArrayList<>(pricePoints);
            rows.add(new NexPricePointView(priceUsdt, deltaPercent, sampledAt));
            pricePoints = rows;
        }

        @Override
        public void ensureNexMarketSeedData(BigDecimal priceUsdt, BigDecimal deltaPercent, String sparklineJson, LocalDateTime sampledAt) {
            if (latestPrice.isEmpty() || latestSparkline.isEmpty()) {
                marketSeeded = true;
                latestPrice = Optional.of(priceUsdt);
                latestSparkline = Optional.ofNullable(sparklineJson);
            }
        }

        @Override
        public void ensureExchangeSeedData() {
            if (orders.isEmpty() || orders.stream().noneMatch(order -> List.of(
                    "QUEUED",
                    "KYC_REQUIRED",
                    "USER_CAP",
                    "PLATFORM_CAP",
                    "GEO_BLOCKED").contains(order.status()))) {
                exchangeSeeded = true;
                orders = defaultExchangeOrders();
            }
        }

        @Override
        public BigDecimal todayExchangeCompletedUsdt() {
            return orders.stream()
                    .filter(order -> "COMPLETED".equals(order.status()) || "SUCCESS".equals(order.status()))
                    .map(ExchangeOrderView::amountUsdt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        @Override
        public long queuedExchangeCount() {
            return orders.stream().filter(order -> "QUEUED".equals(order.status())).count();
        }

        @Override
        public long todayExchangeCountByStatus(String status) {
            return orders.stream().filter(order -> status.equals(order.status())).count();
        }

        @Override
        public List<ExchangeOrderView> exchangeOrdersByStatuses(List<String> statuses, int limit) {
            return orders.stream()
                    .filter(order -> statuses.contains(order.status()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public Optional<ExchangeOrderView> findExchangeOrder(String exchangeNo) {
            return orders.stream().filter(order -> order.exchangeNo().equals(exchangeNo)).findFirst();
        }

        @Override
        public boolean updateExchangeStatus(String exchangeNo, String status) {
            orders = orders.stream()
                    .map(order -> order.exchangeNo().equals(exchangeNo) ? exchangeOrderWithStatus(order, status) : order)
                    .toList();
            return true;
        }

        @Override
        public boolean updateExchangeStatusIfCurrent(String exchangeNo, String status, List<String> currentStatuses) {
            if (failConditionalUpdate) {
                return false;
            }
            Optional<ExchangeOrderView> current = findExchangeOrder(exchangeNo);
            if (current.isEmpty() || !currentStatuses.contains(current.get().status())) {
                return false;
            }
            return updateExchangeStatus(exchangeNo, status);
        }

        @Override
        public boolean cancelQueuedExchange(String exchangeNo) {
            cancelled.add(exchangeNo);
            return true;
        }

        @Override
        public void ensureStakingSeedData() {
            if (stakingProducts.isEmpty() || stakingPositions.isEmpty()) {
                stakingSeeded = true;
            }
            if (stakingProducts.isEmpty()) {
                stakingProducts = defaultStakingProducts();
            }
            if (stakingPositions.isEmpty()) {
                stakingPositions = defaultStakingPositions();
            }
        }

        @Override
        public List<StakingProductView> stakingProducts() {
            return stakingProducts;
        }

        @Override
        public BigDecimal stakingEstimatedInterestUsdt() {
            return stakingPositions.stream()
                    .filter(position -> List.of("ACTIVE", "MATURE_UNCLAIMED").contains(position.status()))
                    .map(StakingPositionView::estimatedInterestUsdt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        @Override
        public long stakingPositionCountByStatus(String status) {
            return stakingPositions.stream()
                    .filter(position -> status.equals(position.status()))
                    .count();
        }

        @Override
        public long stakingEarlyWithdrawnCountSince(LocalDateTime since) {
            return stakingPositions.stream()
                    .filter(position -> "EARLY_WITHDRAWN".equals(position.status()))
                    .filter(position -> position.earlyWithdrawnAt() != null && !position.earlyWithdrawnAt().isBefore(since))
                    .count();
        }

        @Override
        public List<StakingPositionView> stakingPositionsByStatus(String status, int limit) {
            return stakingPositions.stream()
                    .filter(position -> status.equals(position.status()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public void ensureRepurchaseSeedData() {
            if (repurchasePositions.isEmpty()) {
                repurchaseSeeded = true;
                repurchasePositions = defaultRepurchasePositions();
            }
        }

        @Override
        public Optional<StakingProductView> repurchaseProduct() {
            return Optional.ofNullable(repurchaseProduct);
        }

        @Override
        public boolean updateRepurchaseApy(BigDecimal apyPct) {
            repurchaseProduct = withRepurchaseProduct(
                    apyPct.multiply(BigDecimal.valueOf(100)),
                    repurchaseProduct.earlyPenaltyBps(),
                    repurchaseProduct.rewardMultiplier(),
                    repurchaseProduct.ticketPerOrder(),
                    repurchaseProduct.presetAmounts());
            return true;
        }

        @Override
        public boolean updateRepurchasePenalty(BigDecimal penaltyPct) {
            repurchaseProduct = withRepurchaseProduct(
                    repurchaseProduct.apyBps(),
                    penaltyPct.multiply(BigDecimal.valueOf(100)),
                    repurchaseProduct.rewardMultiplier(),
                    repurchaseProduct.ticketPerOrder(),
                    repurchaseProduct.presetAmounts());
            return true;
        }

        @Override
        public boolean updateRepurchaseRewardMultiplier(BigDecimal multiplier) {
            repurchaseProduct = withRepurchaseProduct(
                    repurchaseProduct.apyBps(),
                    repurchaseProduct.earlyPenaltyBps(),
                    multiplier,
                    repurchaseProduct.ticketPerOrder(),
                    repurchaseProduct.presetAmounts());
            return true;
        }

        @Override
        public boolean updateRepurchaseTicketPerOrder(int ticketPerOrder) {
            repurchaseProduct = withRepurchaseProduct(
                    repurchaseProduct.apyBps(),
                    repurchaseProduct.earlyPenaltyBps(),
                    repurchaseProduct.rewardMultiplier(),
                    ticketPerOrder,
                    repurchaseProduct.presetAmounts());
            return true;
        }

        @Override
        public boolean updateRepurchasePresetAmounts(String presetAmounts) {
            repurchaseProduct = withRepurchaseProduct(
                    repurchaseProduct.apyBps(),
                    repurchaseProduct.earlyPenaltyBps(),
                    repurchaseProduct.rewardMultiplier(),
                    repurchaseProduct.ticketPerOrder(),
                    presetAmounts);
            return true;
        }

        @Override
        public RepurchaseStatsView repurchaseStatsSince(LocalDateTime since) {
            long ordersMonth = repurchasePositions.stream()
                    .filter(position -> !position.lockedAt().isBefore(since))
                    .count();
            BigDecimal principalUsd = repurchasePositions.stream()
                    .filter(position -> List.of("PENDING_LOCK", "ACTIVE", "MATURE_UNCLAIMED").contains(position.status()))
                    .map(StakingPositionView::amountUsdt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal estimatedInterestUsd = repurchasePositions.stream()
                    .filter(position -> List.of("PENDING_LOCK", "ACTIVE", "MATURE_UNCLAIMED").contains(position.status()))
                    .map(StakingPositionView::estimatedInterestUsdt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new RepurchaseStatsView(ordersMonth, principalUsd, estimatedInterestUsd);
        }

        @Override
        public List<RepurchaseStatusView> repurchaseStatusBreakdown() {
            return List.of("PENDING_LOCK", "ACTIVE", "MATURE_UNCLAIMED", "EARLY_WITHDRAWN").stream()
                    .map(status -> {
                        List<StakingPositionView> rows = repurchasePositions.stream()
                                .filter(position -> status.equals(position.status()))
                                .toList();
                        BigDecimal amountUsd = rows.stream()
                                .map(StakingPositionView::amountUsdt)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                        return new RepurchaseStatusView(status, (long) rows.size(), amountUsd);
                    })
                    .filter(row -> row.orderCount() > 0)
                    .toList();
        }

        @Override
        public List<RepurchaseAmountBucketView> repurchaseAmountBuckets() {
            Map<BigDecimal, List<StakingPositionView>> buckets = repurchasePositions.stream()
                    .filter(position -> List.of("PENDING_LOCK", "ACTIVE", "MATURE_UNCLAIMED").contains(position.status()))
                    .collect(java.util.stream.Collectors.groupingBy(StakingPositionView::amountUsdt, java.util.TreeMap::new, java.util.stream.Collectors.toList()));
            return buckets.entrySet().stream()
                    .map(entry -> new RepurchaseAmountBucketView(
                            entry.getKey(),
                            (long) entry.getValue().size(),
                            entry.getValue().stream()
                                    .map(StakingPositionView::amountUsdt)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add)))
                    .toList();
        }

        @Override
        public void ensureGenesisSeedData() {
            if (genesisSeries.isEmpty() || genesisNodes.isEmpty()) {
                genesisSeeded = true;
                genesisSeries = Optional.of(defaultGenesisSeries());
                genesisSecondaryStats = defaultGenesisSecondaryStats();
                genesisNodes = defaultGenesisNodes();
            }
        }

        @Override
        public Optional<GenesisSeriesView> activeGenesisSeries() {
            return genesisSeries;
        }

        @Override
        public Optional<GenesisPolicyView> activeGenesisPolicy() {
            return genesisSeries.isEmpty() ? Optional.empty() : genesisPolicy;
        }

        @Override
        public boolean updateGenesisTotalSupply(int totalSupply) {
            GenesisPolicyView current = genesisPolicy.orElse(defaultGenesisPolicy());
            genesisPolicy = Optional.of(new GenesisPolicyView(
                    totalSupply,
                    current.soldSupply(),
                    current.priceUsdt(),
                    current.dailyDividendRatePct(),
                    current.royaltyPct(),
                    current.dividendBaseFormula()));
            return true;
        }

        @Override
        public boolean updateGenesisPrice(BigDecimal priceUsdt) {
            GenesisPolicyView current = genesisPolicy.orElse(defaultGenesisPolicy());
            genesisPolicy = Optional.of(new GenesisPolicyView(
                    current.totalSupply(),
                    current.soldSupply(),
                    priceUsdt,
                    current.dailyDividendRatePct(),
                    current.royaltyPct(),
                    current.dividendBaseFormula()));
            return true;
        }

        @Override
        public boolean updateGenesisDailyDividendRate(BigDecimal dailyDividendRatePct) {
            GenesisPolicyView current = genesisPolicy.orElse(defaultGenesisPolicy());
            genesisPolicy = Optional.of(new GenesisPolicyView(
                    current.totalSupply(),
                    current.soldSupply(),
                    current.priceUsdt(),
                    dailyDividendRatePct,
                    current.royaltyPct(),
                    current.dividendBaseFormula()));
            return true;
        }

        @Override
        public boolean updateGenesisRoyalty(BigDecimal royaltyPct) {
            GenesisPolicyView current = genesisPolicy.orElse(defaultGenesisPolicy());
            genesisPolicy = Optional.of(new GenesisPolicyView(
                    current.totalSupply(),
                    current.soldSupply(),
                    current.priceUsdt(),
                    current.dailyDividendRatePct(),
                    royaltyPct,
                    current.dividendBaseFormula()));
            return true;
        }

        @Override
        public boolean updateGenesisDividendBaseFormula(String formula) {
            GenesisPolicyView current = genesisPolicy.orElse(defaultGenesisPolicy());
            genesisPolicy = Optional.of(new GenesisPolicyView(
                    current.totalSupply(),
                    current.soldSupply(),
                    current.priceUsdt(),
                    current.dailyDividendRatePct(),
                    current.royaltyPct(),
                    formula));
            return true;
        }

        @Override
        public GenesisSecondaryStatsView genesisSecondaryStats(LocalDateTime since) {
            return genesisSecondaryStats;
        }

        @Override
        public BigDecimal genesisAccrualUsd() {
            return BigDecimal.ZERO;
        }

        @Override
        public Optional<String> latestGenesisDividendRerunBatchNo() {
            return genesisDividendReruns.stream().reduce((first, second) -> second);
        }

        @Override
        public boolean genesisDividendBatchRerunExists(String batchNo) {
            return genesisDividendReruns.contains(batchNo);
        }

        @Override
        public long genesisHoldingCount() {
            return genesisNodes.size();
        }

        @Override
        public List<GenesisNodeView> genesisNodes(int offset, int limit) {
            return genesisNodes.stream().skip(offset).limit(limit).toList();
        }

        private static GenesisSeriesView defaultGenesisSeries() {
            return new GenesisSeriesView(
                    1L,
                    "GENESIS-2026",
                    "Genesis Node",
                    1000,
                    847,
                    new BigDecimal("9999"),
                    250,
                    "ACTIVE");
        }

        private static GenesisPolicyView defaultGenesisPolicy() {
            return new GenesisPolicyView(
                    1000,
                    847,
                    new BigDecimal("9999"),
                    new BigDecimal("0.1"),
                    new BigDecimal("2.5"),
                    "24h Genesis completed volume * daily rate / active slots");
        }

        private static GenesisSecondaryStatsView defaultGenesisSecondaryStats() {
            return new GenesisSecondaryStatsView(
                    new BigDecimal("12400"),
                    new BigDecimal("186000"),
                    38L,
                    612L);
        }

        private static List<GenesisNodeView> defaultGenesisNodes() {
            LocalDateTime now = LocalDateTime.parse("2026-06-17T00:00:00");
            return List.of(
                    genesisNode(42L, 10042L, "#0042", "usr_31E8", "primary", "ACTIVE", now.minusDays(160), new BigDecimal("9999")),
                    genesisNode(117L, 10117L, "#0117", "usr_19C7", "secondary transfer 05-12", "ACTIVE", now.minusDays(29), new BigDecimal("11200")),
                    genesisNode(233L, 10233L, "#0233", "usr_84F2", "primary", "LISTED", now.minusDays(125), new BigDecimal("12800")));
        }

        private static GenesisNodeView genesisNode(
                Long id,
                Long userId,
                String holdingNo,
                String ownerCode,
                String sourceLabel,
                String status,
                LocalDateTime acquiredAt,
                BigDecimal acquiredPriceUsdt) {
            return new GenesisNodeView(
                    id,
                    userId,
                    "U" + String.format("%08d", userId),
                    ownerCode,
                    holdingNo,
                    "GENESIS-2026",
                    acquiredPriceUsdt,
                    sourceLabel,
                    status,
                    "LISTED".equals(status) ? "二级挂单中" : "持有计分红",
                    "LISTED".equals(status) ? "dim" : "ok",
                    acquiredAt,
                    acquiredAt.plusDays(1));
        }

        private static List<StakingProductView> defaultStakingProducts() {
            return List.of(
                    stakingProduct(1L, "USDT_30D", 30, "1200", "500", "100", "100"),
                    stakingProduct(2L, "USDT_90D", 90, "3500", "1500", "500", "1000"),
                    stakingProduct(3L, "USDT_180D", 180, "8000", "3000", "1000", "1000"),
                    stakingProduct(4L, "USDT_365D", 365, "18000", "5000", "5000", "5000"));
        }

        private static StakingProductView defaultRepurchaseProduct() {
            return new StakingProductView(
                    5L,
                    "REPURCHASE_90D",
                    "Repurchase · 90d",
                    "USDT",
                    90,
                    new BigDecimal("3500"),
                    new BigDecimal("1500"),
                    new BigDecimal("100"),
                    new BigDecimal("1.5"),
                    1,
                    "$100 / 200 / 500 / 1,000",
                    50,
                    "ACTIVE",
                    new BigDecimal("16800"),
                    6L);
        }

        private StakingProductView withRepurchaseProduct(
                BigDecimal apyBps,
                BigDecimal penaltyBps,
                BigDecimal rewardMultiplier,
                Integer ticketPerOrder,
                String presetAmounts) {
            return new StakingProductView(
                    repurchaseProduct.id(),
                    repurchaseProduct.productCode(),
                    repurchaseProduct.productName(),
                    repurchaseProduct.asset(),
                    repurchaseProduct.termDays(),
                    apyBps,
                    penaltyBps,
                    repurchaseProduct.minAmount(),
                    rewardMultiplier,
                    ticketPerOrder,
                    presetAmounts,
                    repurchaseProduct.sortOrder(),
                    repurchaseProduct.status(),
                    repurchaseProduct.lockedUsd(),
                    repurchaseProduct.positionCount());
        }

        private static StakingProductView stakingProduct(
                long id,
                String productCode,
                int termDays,
                String apyBps,
                String penaltyBps,
                String minAmount,
                String lockedUsd) {
            return new StakingProductView(
                    id,
                    productCode,
                    "USDT · " + termDays + "d",
                    "USDT",
                    termDays,
                    new BigDecimal(apyBps),
                    new BigDecimal(penaltyBps),
                    new BigDecimal(minAmount),
                    BigDecimal.ZERO,
                    0,
                    "",
                    (int) id * 10,
                    "ACTIVE",
                    new BigDecimal(lockedUsd),
                    1L);
        }

        private static List<StakingPositionView> defaultStakingPositions() {
            LocalDateTime now = LocalDateTime.parse("2026-06-17T00:00:00");
            return List.of(
                    stakingPosition(1L, "POS-9081", "USDT_90D", "USDT · 90d", "500", "3500", "1500", 90, "PENDING_LOCK", now.minusDays(1), now.plusDays(89), "0", null),
                    stakingPosition(2L, "POS-7102", "USDT_180D", "USDT · 180d", "1000", "8000", "3000", 180, "ACTIVE", now.minusDays(90), now.plusDays(90), "197.26", null),
                    stakingPosition(3L, "POS-6339", "USDT_365D", "USDT · 365d", "5000", "18000", "5000", 365, "ACTIVE", now.minusDays(120), now.plusDays(245), "295.89", null),
                    stakingPosition(4L, "POS-5412", "USDT_30D", "USDT · 30d", "100", "1200", "500", 30, "MATURE_UNCLAIMED", now.minusDays(40), now.minusDays(10), "0.99", null),
                    stakingPosition(5L, "POS-5520", "USDT_90D", "USDT · 90d", "500", "3500", "1500", 90, "MATURE_UNCLAIMED", now.minusDays(100), now.minusDays(10), "43.15", null),
                    stakingPosition(6L, "POS-5019", "USDT_365D", "USDT · 365d", "5000", "18000", "5000", 365, "EARLY_WITHDRAWN", now.minusDays(30), now.plusDays(335), "0", now.minusDays(2)));
        }

        private static List<StakingPositionView> defaultRepurchasePositions() {
            LocalDateTime now = LocalDateTime.parse("2026-06-17T00:00:00");
            return List.of(
                    stakingPosition(101L, "RPI-1001", "REPURCHASE_90D", "复投激励 · 90d", "100", "3500", "1500", 90, "PENDING_LOCK", now.minusDays(1), now.plusDays(89), "8.63", null),
                    stakingPosition(102L, "RPI-1002", "REPURCHASE_90D", "复投激励 · 90d", "200", "3500", "1500", 90, "ACTIVE", now.minusDays(7), now.plusDays(83), "17.26", null),
                    stakingPosition(103L, "RPI-1003", "REPURCHASE_90D", "复投激励 · 90d", "500", "3500", "1500", 90, "ACTIVE", now.minusDays(12), now.plusDays(78), "43.15", null),
                    stakingPosition(104L, "RPI-1004", "REPURCHASE_90D", "复投激励 · 90d", "1000", "3500", "1500", 90, "MATURE_UNCLAIMED", now.minusDays(95), now.minusDays(5), "86.30", null),
                    stakingPosition(105L, "RPI-1005", "REPURCHASE_90D", "复投激励 · 90d", "5000", "3500", "1500", 90, "MATURE_UNCLAIMED", now.minusDays(99), now.minusDays(9), "431.51", null),
                    stakingPosition(106L, "RPI-1006", "REPURCHASE_90D", "复投激励 · 90d", "10000", "3500", "1500", 90, "EARLY_WITHDRAWN", now.minusDays(32), now.plusDays(58), "0", now.minusDays(1)));
        }

        private static List<ExchangeOrderView> defaultExchangeOrders() {
            return List.of(
                    exchange("DEMO-EX-NEX-USDT-1", "COMPLETED"),
                    exchange("DEMO-EX-QUEUE-1", "QUEUED"),
                    exchange("DEMO-EX-QUEUE-2", "QUEUED"),
                    exchange("DEMO-EX-KYC-1", "KYC_REQUIRED"),
                    exchange("DEMO-EX-USERCAP-1", "USER_CAP"),
                    exchange("DEMO-EX-PLATFORMCAP-1", "PLATFORM_CAP"));
        }

        private static StakingPositionView stakingPosition(
                long id,
                String positionNo,
                String productCode,
                String productName,
                String amountUsdt,
                String apyBps,
                String earlyPenaltyBps,
                int termDays,
                String status,
                LocalDateTime lockedAt,
                LocalDateTime unlockAt,
                String interestUsdt,
                LocalDateTime earlyWithdrawnAt) {
            return new StakingPositionView(
                    id,
                    10000L + id,
                    "U" + String.format("%08d", 10000 + id),
                    "demo-user-" + id,
                    positionNo,
                    productCode,
                    productName,
                    new BigDecimal(amountUsdt),
                    new BigDecimal(apyBps),
                    new BigDecimal(earlyPenaltyBps),
                    termDays,
                    lockedAt,
                    unlockAt,
                    new BigDecimal(interestUsdt),
                    status,
                    status,
                    "ACTIVE".equals(status) ? "ok" : "warn",
                    null,
                    earlyWithdrawnAt);
        }
    }
}
