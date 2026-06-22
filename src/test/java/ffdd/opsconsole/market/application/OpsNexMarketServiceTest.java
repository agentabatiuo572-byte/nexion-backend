package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.market.domain.ExchangeOrderView;
import ffdd.opsconsole.market.domain.NexMarketRepository;
import ffdd.opsconsole.market.dto.ExchangeParamUpdateRequest;
import ffdd.opsconsole.market.dto.ExchangeQueueCancelRequest;
import ffdd.opsconsole.market.dto.ExchangeSwapStatusRequest;
import ffdd.opsconsole.market.dto.NexMarketAdvanceRequest;
import ffdd.opsconsole.market.dto.NexMarketCurveFrame;
import ffdd.opsconsole.market.dto.NexMarketCurveUpdateRequest;
import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsNexMarketServiceTest {
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final FakeTreasuryCoverageFacade coverageFacade = new FakeTreasuryCoverageFacade();
    private final FakeNexMarketRepository marketRepository = new FakeNexMarketRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-17T00:00:00Z"), ZoneId.of("UTC"));
    private final OpsNexMarketService service = service();

    private OpsNexMarketService service() {
        OpsNexMarketService service = new OpsNexMarketService(
                configFacade,
                coverageFacade,
                marketRepository,
                auditLogService,
                new ObjectMapper(),
                clock);
        return service;
    }

    @Test
    void overviewExposesSevenFrameCurveAndSunsetExclusions() {
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
    void exchangeOverviewUsesServerOrdersConfigAndJ1Switch() {
        marketRepository.orders = List.of(exchange("EX-Q-1", "QUEUED"), exchange("EX-KYC-1", "KYC_REQUIRED"));
        configFacade.values.put("wallet.exchange.platform_daily_cap_usdt", "20000");
        configFacade.values.put("emergency.killswitch.exchange", "off");

        ApiResult<Map<String, Object>> result = service.exchangeOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("domain", "G2");
        assertThat((List<?>) result.getData().get("queue")).hasSize(1);
        assertThat(detailMap(result.getData().get("swap"))).containsEntry("enabled", false);
        assertThat((List<?>) result.getData().get("geoBlocked")).isNotEmpty();
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
        configFacade.values.put("killswitch.exchange", "disabled");

        ApiResult<Map<String, Object>> result = service.updateExchangeSwapStatus(
                "idem-g2-swap",
                new ExchangeSwapStatusRequest(true, "restore exchange", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(configFacade.values).containsEntry("killswitch.exchange", "disabled");
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
    void raisingCurveBelowB1CoverageRedlineReturns422() {
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));

        ApiResult<Map<String, Object>> result = service.updateWeeklyCurve(
                "idem-g3",
                new NexMarketCurveUpdateRequest(frames("0.200"), "raise price", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
    }

    @Test
    void validCurveWritesConfigPublishesActiveFrameAndAudits() {
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
    void updateControlWritesConfigAndAudits() {
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
    void updateOverridePricePublishesMarketPriceAndAudits() {
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
        configFacade.values.put("G.repurchase.apy", "35");
        configFacade.values.put("G.repurchase.presets", "$100 / 200 / 500 / 1,000");

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
    void raisingRepurchaseApyBelowCoverageRedlineReturns422() {
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));
        configFacade.values.put("G.repurchase.apy", "35");

        ApiResult<Map<String, Object>> result = service.updateRepurchaseParam(
                "idem-g7-apy",
                "apy",
                new NexMarketValueUpdateRequest("40", "raise repurchase apy", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(configFacade.values).containsEntry("G.repurchase.apy", "35");
    }

    @Test
    void updatingRepurchasePresetWritesConfigAndAudits() {
        ApiResult<Map<String, Object>> result = service.updateRepurchaseParam(
                "idem-g7-preset",
                "presets",
                new NexMarketValueUpdateRequest("$100 / 300 / 500", "campaign presets", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("G.repurchase.presets", "$100 / 300 / 500");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("G7_REPURCHASE_PARAM_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-g7-preset");
    }

    @Test
    void genesisOverviewExposesServerCanonicalNodeLedgerAndJ1Switch() {
        configFacade.values.put("J.killswitch.genesis", "off");

        ApiResult<Map<String, Object>> result = service.genesisOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("domain", "G4");
        assertThat(detailMap(result.getData().get("market"))).containsEntry("enabled", false);
        assertThat((List<?>) result.getData().get("params"))
                .extracting("key")
                .contains("supply", "price", "dividend", "royalty", "divBase");
        assertThat((List<?>) result.getData().get("nodes"))
                .extracting("id")
                .contains("#0042", "#0117", "#0233");
        assertThat(result.getData().get("sunsetExclusions"))
                .asList()
                .contains("Premium", "NEX v2", "Points");
    }

    @Test
    void raisingGenesisDividendBelowCoverageRedlineReturns422() {
        coverageFacade.snapshot = new TreasuryCoverageSnapshot(new BigDecimal("80.00"), new BigDecimal("85.00"));
        configFacade.values.put("G.genesis.dividend", "0.1");

        ApiResult<Map<String, Object>> result = service.updateGenesisParam(
                "idem-g4-dividend",
                "dividend",
                new NexMarketValueUpdateRequest("0.2", "raise genesis dividend", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(configFacade.values).containsEntry("G.genesis.dividend", "0.1");
    }

    @Test
    void updatingGenesisRoyaltyWritesConfigAndAudits() {
        ApiResult<Map<String, Object>> result = service.updateGenesisParam(
                "idem-g4-royalty",
                "royalty",
                new NexMarketValueUpdateRequest("3.0", "secondary market policy", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("G.genesis.royalty", "3");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("G4_GENESIS_PARAM_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-g4-royalty");
    }

    @Test
    void rerunGenesisDividendBatchWritesMarkerAndAudits() {
        ApiResult<Map<String, Object>> result = service.rerunGenesisDividendBatch(
                "idem-g4-rerun",
                "GD-0611",
                new NexMarketValueUpdateRequest("rerun", "retry failed payout rows", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("G.genesis.rerun.GD-0611", "done");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("G4_GENESIS_DIVIDEND_BATCH_RERUN");
    }

    @Test
    void stakingOverviewExposesServerCanonicalPoolsPositionsAndJ1Switch() {
        configFacade.values.put("J.killswitch.staking", "off");
        configFacade.values.put("G.staking.usdt180d.killed", "true");

        ApiResult<Map<String, Object>> result = service.stakingOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("domain", "G1");
        assertThat(detailMap(result.getData().get("stats"))).containsEntry("stakingGateOn", false);
        assertThat((List<?>) result.getData().get("pools"))
                .extracting("tierKey")
                .contains("usdt30d", "usdt180d", "nex365d");
        assertThat((List<?>) result.getData().get("positions"))
                .extracting("status")
                .contains("pending_lock", "active", "mature_unclaimed", "early_withdrawn");
        assertThat(result.getData().get("sunsetExclusions"))
                .asList()
                .contains("Premium", "NEX v2", "Points");
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
                "nex180d",
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
        verify(auditLogService).record(captor.capture());
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
    void killingStakingTierWritesConfigAndAudits() {
        ApiResult<Map<String, Object>> result = service.updateStakingPoolKillStatus(
                "idem-g1-kill",
                "nex365d",
                new NexMarketValueUpdateRequest("true", "slash open positions by published incident plan", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("G.staking.nex365d.killed", "true");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("G1_STAKING_POOL_KILL_STATUS_CHANGED");
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

    private static ExchangeOrderView exchange(String exchangeNo, String status) {
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
                new BigDecimal("17.10"),
                new BigDecimal("0.171"),
                status,
                status,
                "warn",
                "NEX→USDT",
                new BigDecimal("17.10"),
                null,
                null,
                "明天 00:00",
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
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

    private static final class FakeTreasuryCoverageFacade implements TreasuryCoverageFacade {
        private TreasuryCoverageSnapshot snapshot = new TreasuryCoverageSnapshot(new BigDecimal("110.00"), new BigDecimal("85.00"));

        @Override
        public TreasuryCoverageSnapshot snapshot() {
            return snapshot;
        }
    }

    private static final class FakeNexMarketRepository implements NexMarketRepository {
        private BigDecimal lastPrice;
        private List<ExchangeOrderView> orders = List.of();
        private final List<String> cancelled = new java.util.ArrayList<>();

        @Override
        public Optional<BigDecimal> latestNexUsdtPrice() {
            return Optional.of(new BigDecimal("0.171"));
        }

        @Override
        public Optional<String> latestNexSparkline() {
            return Optional.empty();
        }

        @Override
        public void publishNexUsdtPrice(BigDecimal priceUsdt, BigDecimal deltaPercent, String sparklineJson, LocalDateTime sampledAt) {
            lastPrice = priceUsdt;
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
        public boolean cancelQueuedExchange(String exchangeNo) {
            cancelled.add(exchangeNo);
            return true;
        }
    }
}
