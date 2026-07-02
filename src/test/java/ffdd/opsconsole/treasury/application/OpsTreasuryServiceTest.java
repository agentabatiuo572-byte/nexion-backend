package ffdd.opsconsole.treasury.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerBillView;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import ffdd.opsconsole.treasury.dto.TreasuryAlertAckRequest;
import ffdd.opsconsole.treasury.dto.TreasuryInjectionRequest;
import ffdd.opsconsole.treasury.dto.TreasuryLedgerAdjustmentRequest;
import ffdd.opsconsole.treasury.dto.TreasuryLedgerQueryRequest;
import ffdd.opsconsole.treasury.dto.TreasuryScopeRequest;
import ffdd.opsconsole.treasury.dto.TreasuryThresholdRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsTreasuryServiceTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-17T00:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final FakeTreasuryLedgerRepository ledgerRepository = new FakeTreasuryLedgerRepository();
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final FakeEmergencyControlRepository emergencyRepository = new FakeEmergencyControlRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final Map<String, String> idempotencyHashes = new LinkedHashMap<>();
    private final Map<String, ApiResult<Map<String, Object>>> idempotencyResponses = new LinkedHashMap<>();
    private final OpsTreasuryService service = service();

    private OpsTreasuryService service() {
        return service(ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction());
    }

    private OpsTreasuryService service(OpsReadTimeSeedPolicy seedPolicy) {
        OpsTreasuryService service = new OpsTreasuryService(
                ledgerRepository,
                configFacade,
                emergencyRepository,
                auditLogService,
                idempotencyService,
                CLOCK,
                new TreasuryDualLedgerProperties(),
                new ObjectMapper(),
                seedPolicy);
        return service;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUpIdempotency() {
        when(idempotencyService.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any(Supplier.class)))
                .thenAnswer(invocation -> {
                    String scope = invocation.getArgument(0);
                    String key = invocation.getArgument(1);
                    String hash = invocation.getArgument(2);
                    Supplier<ApiResult<Map<String, Object>>> action = invocation.getArgument(4);
                    String cacheKey = scope + ":" + key;
                    if (idempotencyResponses.containsKey(cacheKey)) {
                        if (!Objects.equals(idempotencyHashes.get(cacheKey), hash)) {
                            throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
                        }
                        return idempotencyResponses.get(cacheKey);
                    }
                    ApiResult<Map<String, Object>> response = action.get();
                    idempotencyHashes.put(cacheKey, hash);
                    idempotencyResponses.put(cacheKey, response);
                    return response;
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void overviewSummarizesServerCanonicalTreasuryStats() {
        ledgerRepository.countValue = 3L;

        ApiResult<Map<String, Object>> result = service.overview(0);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("service", "nexion-backend")
                .containsEntry("domain", "B")
                .containsEntry("days", 7);
        assertThat((Map<String, Object>) result.getData().get("deposits"))
                .containsEntry("total", 3L)
                .containsEntry("success", 3L);
        assertThat((Map<String, Object>) result.getData().get("dualLedger")).containsKey("snapshot");
    }

    @Test
    @SuppressWarnings("unchecked")
    void disabledReadTimeSeedsDoNotExposeB5SeededRiskGates() {
        OpsTreasuryService realOnlyService = service(OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        ApiResult<Map<String, Object>> result = realOnlyService.bDomainDashboard();

        assertThat(result.getCode()).isZero();
        Map<String, Object> riskRadar = (Map<String, Object>) result.getData().get("riskRadar");
        assertThat((List<Map<String, Object>>) riskRadar.get("gates")).isEmpty();
        assertThat(riskRadar).containsEntry("trippedGateCount", 0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dualLedgerAggregatesLiabilitiesWithoutSunsetProducts() {
        configFacade.values.put("H1.rhythm.currentMonth", "8");
        configFacade.values.put("growth.phase.current", "P4");
        ledgerRepository.usdtAvailable = new BigDecimal("1000");
        ledgerRepository.pendingWithdraw = new BigDecimal("150");
        ledgerRepository.nexAvailable = new BigDecimal("2000");
        ledgerRepository.stakingPrincipal = new BigDecimal("500");
        ledgerRepository.stakingInterest = new BigDecimal("50");
        ledgerRepository.nexLocked = new BigDecimal("1000");
        ledgerRepository.nexReward = new BigDecimal("100");
        ledgerRepository.withdrawalQueue = new BigDecimal("300");
        ledgerRepository.activeQueueCount = 3L;
        ledgerRepository.avgRiskScore = new BigDecimal("42.4");
        ledgerRepository.pendingCommission = new BigDecimal("80");
        ledgerRepository.netFlow = new BigDecimal("-20");

        Map<String, Object> dualLedger = service.dualLedger().getData();

        Map<String, Object> snapshot = (Map<String, Object>) dualLedger.get("snapshot");
        assertThat(snapshot)
                .containsEntry("reserveUsd", new BigDecimal("5000.00"))
                .containsEntry("liabilitiesUsd", new BigDecimal("2607.00"))
                .containsEntry("queueBacklogCount", 3L)
                .containsEntry("avgRiskScore", 42L);
        assertThat((Iterable<Map<String, Object>>) dualLedger.get("accounts"))
                .extracting(account -> account.get("key"))
                .contains("nex_payable")
                .doesNotContain("nexv2", "premium", "points");
        assertThat(dualLedger.get("h1Rhythm"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("currentMonth", 8)
                .containsEntry("currentPhase", "P4");
    }

    @Test
    @SuppressWarnings("unchecked")
    void disabledReadTimeSeedsReadSafetyThresholdsFromBaseConfig() {
        OpsTreasuryService realOnlyService = service(OpsReadTimeSeedPolicy.disabledForDirectConstruction());
        ledgerRepository.usdtAvailable = new BigDecimal("100");

        ApiResult<Map<String, Object>> result = realOnlyService.dualLedger();

        assertThat(result.getCode()).isZero();
        Map<String, Object> snapshot = (Map<String, Object>) result.getData().get("snapshot");
        assertThat(snapshot)
                .containsEntry("reserveUsd", new BigDecimal("5000.00"))
                .containsEntry("liabilitiesUsd", new BigDecimal("100.00"))
                .containsEntry("coverageRatio", new BigDecimal("5000.00"))
                .containsEntry("redlinePct", new BigDecimal("85.00"))
                .containsEntry("healthyPct", new BigDecimal("100.00"))
                .containsEntry("runRiskPct", new BigDecimal("15.00"))
                .containsEntry("redlineBreached", false);
        assertThat(configFacade.upsertedKeys).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dualLedgerDoesNotCreateFallbackTreasuryRowsWhenDatabaseIsEmpty() {
        Map<String, Object> dualLedger = service.dualLedger().getData();

        assertThat((Map<String, Object>) dualLedger.get("snapshot"))
                .containsEntry("liabilitiesUsd", new BigDecimal("0.00"));
        assertThat(configFacade.values)
                .doesNotContainKeys("wallet.dual-ledger.reserve-usd", "wallet.dual-ledger.nex-usd-rate")
                .containsKeys("wallet.dual-ledger.redline-pct", "wallet.dual-ledger.scope");
    }

    @Test
    void bDomainDashboardDoesNotSeedD3OrD4FallbackDataWhenTreasuryIsEmpty() {
        Map<String, Object> dashboard = service.bDomainDashboard().getData();

        assertThat(dashboard).containsEntry("domain", "B");
        assertThat(configFacade.upsertedKeys).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void bDomainDashboardIgnoresTreasuryBConfigRowsAndUsesBusinessAggregates() {
        ledgerRepository.usdtAvailable = new BigDecimal("1000");
        ledgerRepository.withdrawalQueue = new BigDecimal("120");
        ledgerRepository.activeQueueCount = 2L;
        ledgerRepository.avgRiskScore = new BigDecimal("36.4");
        ledgerRepository.countValue = 10L;
        emergencyRepository.settings.put("killswitch.withdraw", "disabled");
        emergencyRepository.settings.put("emergency.killswitch.exchange", "off");
        configFacade.values.put("treasury.b.liquidity.runway", "[{\"day\":\"D0\",\"cash\":\"1,000\",\"need\":\"120\",\"gap\":\"880\"}]");
        configFacade.values.put("treasury.b.funnel.stages", "[{\"key\":\"reg\",\"label\":\"注册\",\"count\":10},{\"key\":\"cash\",\"label\":\"入金\",\"count\":3}]");
        configFacade.values.put("treasury.b.risk.rules", "[{\"dom\":\"manual\",\"state\":\"on\",\"on\":true}]");

        Map<String, Object> dashboard = service.bDomainDashboard().getData();

        assertThat(dashboard)
                .containsEntry("domain", "B")
                .containsKeys("dualLedger", "liquidity", "funnel", "rhythm", "riskRadar");
        assertThat(configFacade.upsertedKeys).isEmpty();
        Map<String, Object> liquidity = (Map<String, Object>) dashboard.get("liquidity");
        assertThat((List<Map<String, Object>>) liquidity.get("runway")).isNotEmpty();
        Map<String, Object> funnel = (Map<String, Object>) dashboard.get("funnel");
        assertThat((List<Map<String, Object>>) funnel.get("stages"))
                .extracting(row -> row.get("key"))
                .contains("deposit", "exchange", "withdraw", "ledger")
                .doesNotContain("reg", "cash");
        assertThat(funnel.toString()).doesNotContain("注册");
        Map<String, Object> riskRadar = (Map<String, Object>) dashboard.get("riskRadar");
        assertThat((List<Map<String, Object>>) riskRadar.get("gates"))
                .anySatisfy(gate -> {
                    assertThat(gate).containsEntry("dom", "withdraw");
                    assertThat(gate).containsEntry("on", false);
                    assertThat(gate).containsEntry("state", "off");
                });
        assertThat((List<Map<String, Object>>) riskRadar.get("gates"))
                .anySatisfy(gate -> {
                    assertThat(gate).containsEntry("dom", "exchange");
                    assertThat(gate).containsEntry("on", false);
                    assertThat(gate).containsEntry("state", "off");
                    assertThat(gate).containsEntry("configKey", "emergency.killswitch.exchange");
                });
        assertThat((List<Map<String, Object>>) riskRadar.get("gates"))
                .extracting(gate -> gate.get("dom"))
                .doesNotContain("staking");
        assertThat(riskRadar).containsEntry("trippedGateCount", 2L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void bDomainDashboardIgnoresCorruptTreasuryBConfigWithoutOverwriting() {
        configFacade.values.put("treasury.b.funnel.stages", "{bad-json");
        configFacade.values.put("treasury.b.funnel.daily-target", "oops");

        Map<String, Object> dashboard = service.bDomainDashboard().getData();

        assertThat((List<Map<String, Object>>) dashboard.get("warnings")).isEmpty();
        assertThat(configFacade.values)
                .containsEntry("treasury.b.funnel.stages", "{bad-json")
                .containsEntry("treasury.b.funnel.daily-target", "oops");
        assertThat(configFacade.upsertedKeys)
                .doesNotContain("treasury.b.funnel.stages", "treasury.b.funnel.daily-target");
        Map<String, Object> funnel = (Map<String, Object>) dashboard.get("funnel");
        assertThat((List<Map<String, Object>>) funnel.get("stages"))
                .extracting(row -> row.get("key"))
                .contains("deposit", "exchange", "withdraw", "ledger");
        assertThat((BigDecimal) funnel.get("dailyTarget")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @SuppressWarnings("unchecked")
    void bDomainAlertAckWritesConfigAndAudits() {
        TreasuryAlertAckRequest request = new TreasuryAlertAckRequest("covered by treasury shift", "superadmin");

        ApiResult<Map<String, Object>> result = service.acknowledgeBDomainAlert("coverage-redline", "idem-b-alert", request);

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("wallet.dual-ledger.alert.coverage-redline.ack", "true");
        assertThat((Map<String, Object>) result.getData().get("alertAck"))
                .containsEntry("alertId", "coverage-redline")
                .containsEntry("acknowledged", true);
        assertThat((Map<String, Object>) result.getData().get("alerts"))
                .containsEntry("coverageRedlineAcked", true);
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("B1_COVERAGE_ALERT_ACKED");
        assertThat(captor.getValue().getActorUsername()).isNull();
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("reason", "covered by treasury shift")
                .containsEntry("operator", "superadmin")
                .containsEntry("idempotencyKey", "idem-b-alert");
    }

    @Test
    void bDomainAlertAckReplaysDuplicateIdempotencyWithoutSideEffects() {
        TreasuryAlertAckRequest request = new TreasuryAlertAckRequest("covered by treasury shift", "superadmin");

        ApiResult<Map<String, Object>> first = service.acknowledgeBDomainAlert("coverage-redline", "idem-b-alert", request);
        ApiResult<Map<String, Object>> second = service.acknowledgeBDomainAlert("coverage-redline", "idem-b-alert", request);

        assertThat(first.getCode()).isZero();
        assertThat(second.getCode()).isZero();
        verify(auditLogService, times(1)).record(any(AuditLogWriteRequest.class));
        assertThat(configFacade.upsertedKeys.stream()
                .filter("wallet.dual-ledger.alert.coverage-redline.ack"::equals)
                .count()).isEqualTo(1);
    }

    @Test
    void bDomainAlertAckRejectsIdempotencyPayloadMismatch() {
        TreasuryAlertAckRequest request = new TreasuryAlertAckRequest("covered by treasury shift", "superadmin");
        TreasuryAlertAckRequest changed = new TreasuryAlertAckRequest("changed reason", "superadmin");

        service.acknowledgeBDomainAlert("coverage-redline", "idem-b-alert", request);

        assertThatThrownBy(() -> service.acknowledgeBDomainAlert("coverage-redline", "idem-b-alert", changed))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
        verify(auditLogService, times(1)).record(any(AuditLogWriteRequest.class));
    }

    @Test
    void bDomainAlertAckRejectsMissingOperator() {
        TreasuryAlertAckRequest request = new TreasuryAlertAckRequest("covered by treasury shift", " ");

        ApiResult<Map<String, Object>> result = service.acknowledgeBDomainAlert("coverage-redline", "idem-b-alert", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.OPERATOR_REQUIRED.httpStatus());
        assertThat(configFacade.values).doesNotContainKey("wallet.dual-ledger.alert.coverage-redline.ack");
        verifyNoInteractions(auditLogService);
    }

    @Test
    void injectionRequiresIdempotencyAndReason() {
        TreasuryInjectionRequest request =
                new TreasuryInjectionRequest(new BigDecimal("100"), "V-1", "reserve top-up", "superadmin");

        assertThat(service.createInjection(" ", request).getCode())
                .isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
        assertThat(service.createInjection("idem-1", new TreasuryInjectionRequest(new BigDecimal("100"), "V-1", " ", "superadmin")).getCode())
                .isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
    }

    @Test
    void injectionWritesReserveLedgerAndAudit() {
        TreasuryInjectionRequest request =
                new TreasuryInjectionRequest(new BigDecimal("250.129"), "V-20260617", "reserve top-up", "superadmin");

        ApiResult<Map<String, Object>> result = service.createInjection("idem-b1", request);

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).doesNotContainKey("wallet.dual-ledger.reserve-usd");
        assertThat(ledgerRepository.reserveUsd).isEqualByComparingTo(new BigDecimal("5250.13"));
        assertThat(ledgerRepository.reserveInjections).hasSize(1);
        assertThat((Map<String, Object>) result.getData().get("injection"))
                .containsEntry("voucherNo", "V-20260617")
                .containsEntry("newReserveUsd", new BigDecimal("5250.13"));

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("B1_TREASURY_RESERVE_INJECTION");
        assertThat(captor.getValue().getRiskLevel()).isEqualTo("HIGH");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("reason", "reserve top-up")
                .containsEntry("idempotencyKey", "idem-b1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectionReplaysDuplicateIdempotencyWithoutIncreasingReserveAgain() {
        TreasuryInjectionRequest request =
                new TreasuryInjectionRequest(new BigDecimal("250.129"), "V-20260617", "reserve top-up", "superadmin");

        ApiResult<Map<String, Object>> first = service.createInjection("idem-b1", request);
        ApiResult<Map<String, Object>> second = service.createInjection("idem-b1", request);

        assertThat(first.getCode()).isZero();
        assertThat(second.getCode()).isZero();
        assertThat(ledgerRepository.reserveUsd).isEqualByComparingTo(new BigDecimal("5250.13"));
        Map<String, Object> injection = (Map<String, Object>) second.getData().get("injection");
        assertThat(injection).containsEntry("voucherNo", "V-20260617");
        assertThat((BigDecimal) injection.get("oldReserveUsd")).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat((BigDecimal) injection.get("newReserveUsd")).isEqualByComparingTo(new BigDecimal("5250.13"));
        verify(auditLogService, times(1)).record(any(AuditLogWriteRequest.class));
        assertThat(ledgerRepository.reserveInjections).hasSize(1);
        assertThat(configFacade.upsertedKeys).doesNotContain("wallet.dual-ledger.reserve-usd");
    }

    @Test
    void injectionRejectsIdempotencyPayloadMismatch() {
        TreasuryInjectionRequest request =
                new TreasuryInjectionRequest(new BigDecimal("250.129"), "V-20260617", "reserve top-up", "superadmin");
        TreasuryInjectionRequest changed =
                new TreasuryInjectionRequest(new BigDecimal("251.13"), "V-20260617", "reserve top-up", "superadmin");

        service.createInjection("idem-b1", request);

        assertThatThrownBy(() -> service.createInjection("idem-b1", changed))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
        assertThat(ledgerRepository.reserveUsd).isEqualByComparingTo(new BigDecimal("5250.13"));
        verify(auditLogService, times(1)).record(any(AuditLogWriteRequest.class));
    }

    @Test
    void scopeUpdateUsesPlatformFacadeAndAudits() {
        TreasuryScopeRequest request = new TreasuryScopeRequest("active liabilities only", "policy change", "superadmin");

        ApiResult<Map<String, Object>> result = service.updateScope("idem-scope", request);

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("wallet.dual-ledger.scope", "active liabilities only");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("B1_DUAL_LEDGER_SCOPE_CHANGED");
    }

    @Test
    void thresholdUpdateWritesTreasuryConfigAndAudits() {
        TreasuryThresholdRequest request = new TreasuryThresholdRequest(
                new BigDecimal("92.36"), null, new BigDecimal("18.24"), "risk policy", "superadmin");

        ApiResult<Map<String, Object>> result = service.updateThresholds("idem-threshold", request);

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("wallet.dual-ledger.redline-pct", "92.4")
                .containsEntry("wallet.dual-ledger.run-risk-pct", "18.2");
        assertThat((Map<String, Object>) result.getData().get("thresholdUpdate"))
                .containsEntry("redlinePct", new BigDecimal("92.4"))
                .containsEntry("runRiskPct", new BigDecimal("18.2"));

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("B1_DUAL_LEDGER_THRESHOLDS_CHANGED");
        assertThat(captor.getValue().getRiskLevel()).isEqualTo("HIGH");
    }

    @Test
    void thresholdUpdateRejectsEqualRedlineAndHealthy() {
        TreasuryThresholdRequest request = new TreasuryThresholdRequest(
                new BigDecimal("95"), new BigDecimal("95"), null, "risk policy", "superadmin");

        ApiResult<Map<String, Object>> result = service.updateThresholds("idem-threshold", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("REDLINE_MUST_BE_BELOW_HEALTHY");
        assertThat(configFacade.upsertedKeys)
                .doesNotContain("wallet.dual-ledger.redline-pct", "wallet.dual-ledger.healthy-pct");
        verifyNoInteractions(auditLogService);
    }

    @Test
    void ledgerBillsReturnsServerPage() {
        ledgerRepository.bills.add(new TreasuryLedgerBillView(
                1L,
                10001L,
                "U00010001",
                "测试用户",
                "WD-1",
                "WITHDRAWAL",
                "USDT",
                "OUT",
                new BigDecimal("25.5"),
                new BigDecimal("74.5"),
                "SUCCESS",
                "withdraw completed",
                LocalDateTime.now(CLOCK),
                LocalDateTime.now(CLOCK)));

        var result = service.ledgerBills(new TreasuryLedgerQueryRequest("withdrawal", 10001L, "WD", 1, 20));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getTotal()).isEqualTo(1);
        assertThat(result.getData().getRecords()).extracting(TreasuryLedgerBillView::bizNo).containsExactly("WD-1");
        assertThat(result.getData().getRecords()).extracting(TreasuryLedgerBillView::userNo).containsExactly("U00010001");
        assertThat(ledgerRepository.lastBillType).isEqualTo("WITHDRAWAL");
        assertThat(ledgerRepository.lastBillUserId).isEqualTo(10001L);
        assertThat(ledgerRepository.lastBillKeyword).isEqualTo("WD");
    }

    @Test
    void ledgerBillsDoesNotCreateFallbackRowsWhenDatabaseIsEmpty() {
        var result = service.ledgerBills(new TreasuryLedgerQueryRequest(null, null, null, 1, 20));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getTotal()).isZero();
        assertThat(result.getData().getRecords()).isEmpty();
    }

    @Test
    void ledgerAdjustmentCreatesPendingReviewAndAudits() {
        TreasuryLedgerAdjustmentRequest request = new TreasuryLedgerAdjustmentRequest(
                10001L,
                "USDT",
                "credit",
                new BigDecimal("12.3456789"),
                "WD-1",
                "ledger repair after reconciliation",
                "superadmin");

        ApiResult<Map<String, Object>> result = service.createLedgerAdjustment("idem-d4", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("userId", 10001L)
                .containsEntry("asset", "USDT")
                .containsEntry("direction", "CREDIT")
                .containsEntry("amount", new BigDecimal("12.345679"))
                .containsEntry("status", "PENDING_REVIEW");
        assertThat(ledgerRepository.adjustments).hasSize(1);
        assertThat(ledgerRepository.adjustments.get(0))
                .containsEntry("userId", 10001L)
                .containsEntry("relatedBizNo", "WD-1");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("D4_LEDGER_ADJUSTMENT_CREATED");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("reason", "ledger repair after reconciliation")
                .containsEntry("idempotencyKey", "idem-d4");
    }

    @Test
    void ledgerAdjustmentRejectsMissingOperator() {
        TreasuryLedgerAdjustmentRequest request = new TreasuryLedgerAdjustmentRequest(
                10001L,
                "USDT",
                "credit",
                new BigDecimal("12.345678"),
                "WD-1",
                "ledger repair after reconciliation",
                " ");

        ApiResult<Map<String, Object>> result = service.createLedgerAdjustment("idem-d4", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.OPERATOR_REQUIRED.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.OPERATOR_REQUIRED.name());
        assertThat(ledgerRepository.adjustments).isEmpty();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void ledgerAdjustmentRejectsCreditWhenBaseConfigWouldBreachRedline() {
        OpsTreasuryService realOnlyService = service(OpsReadTimeSeedPolicy.disabledForDirectConstruction());
        ledgerRepository.reserveUsd = BigDecimal.ZERO;
        ledgerRepository.usdtAvailable = new BigDecimal("100");
        TreasuryLedgerAdjustmentRequest request = new TreasuryLedgerAdjustmentRequest(
                10001L,
                "USDT",
                "credit",
                new BigDecimal("12.345678"),
                "WD-1",
                "ledger repair after reconciliation",
                "superadmin");

        ApiResult<Map<String, Object>> result = realOnlyService.createLedgerAdjustment("idem-d4-redline", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("B1_COVERAGE_REDLINE_BREACHED");
        assertThat(ledgerRepository.adjustments).isEmpty();
        assertThat(configFacade.upsertedKeys).isEmpty();
        verifyNoInteractions(auditLogService);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
    }

    private static final class FakePlatformConfigFacade implements PlatformConfigFacade {
        private final Map<String, String> values = new LinkedHashMap<>();
        private final List<String> upsertedKeys = new ArrayList<>();

        private FakePlatformConfigFacade() {
            values.put("wallet.dual-ledger.redline-pct", "85");
            values.put("wallet.dual-ledger.healthy-pct", "100");
            values.put("wallet.dual-ledger.run-risk-pct", "15");
            values.put("wallet.dual-ledger.scope", "all active liabilities");
        }

        @Override
        public Optional<String> activeValue(String configKey) {
            return Optional.ofNullable(values.get(configKey));
        }

        @Override
        public void upsertAdminValue(String configKey, String configValue, String valueType, String configGroup, String remark) {
            upsertedKeys.add(configKey);
            values.put(configKey, configValue);
        }
    }

    private static final class FakeEmergencyControlRepository implements EmergencyControlRepository {
        private final Map<String, String> settings = new LinkedHashMap<>();

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

    private static final class FakeTreasuryLedgerRepository implements TreasuryLedgerRepository {
        private long countValue;
        private long activeQueueCount;
        private BigDecimal usdtAvailable = BigDecimal.ZERO;
        private BigDecimal pendingWithdraw = BigDecimal.ZERO;
        private BigDecimal nexAvailable = BigDecimal.ZERO;
        private BigDecimal stakingPrincipal = BigDecimal.ZERO;
        private BigDecimal stakingInterest = BigDecimal.ZERO;
        private BigDecimal nexLocked = BigDecimal.ZERO;
        private BigDecimal nexReward = BigDecimal.ZERO;
        private BigDecimal withdrawalQueue = BigDecimal.ZERO;
        private BigDecimal avgRiskScore = BigDecimal.ZERO;
        private BigDecimal pendingCommission = BigDecimal.ZERO;
        private BigDecimal netFlow = BigDecimal.ZERO;
        private BigDecimal reserveUsd = new BigDecimal("5000");
        private BigDecimal nexUsdRate = new BigDecimal("0.17");
        private final List<TreasuryLedgerBillView> bills = new ArrayList<>();
        private final List<Map<String, Object>> adjustments = new ArrayList<>();
        private final List<Map<String, Object>> reserveInjections = new ArrayList<>();
        private String lastBillType;
        private Long lastBillUserId;
        private String lastBillKeyword;

        @Override
        public long countDeposits(LocalDateTime since, String status) {
            return countValue;
        }

        @Override
        public long countWithdrawals(LocalDateTime since, String status) {
            return countValue;
        }

        @Override
        public long countExchanges(LocalDateTime since, String status) {
            return countValue;
        }

        @Override
        public long countLedgers(LocalDateTime since, String direction) {
            return countValue;
        }

        @Override
        public BigDecimal sumUsdtAvailable() {
            return usdtAvailable;
        }

        @Override
        public BigDecimal sumPendingWithdraw() {
            return pendingWithdraw;
        }

        @Override
        public BigDecimal sumNexAvailable() {
            return nexAvailable;
        }

        @Override
        public BigDecimal sumActiveStakingPrincipalUsdt() {
            return stakingPrincipal;
        }

        @Override
        public BigDecimal sumActiveStakingInterestUsdt() {
            return stakingInterest;
        }

        @Override
        public BigDecimal sumActiveNexLocked() {
            return nexLocked;
        }

        @Override
        public BigDecimal sumActiveNexReward() {
            return nexReward;
        }

        @Override
        public BigDecimal sumActiveWithdrawalQueueUsdt() {
            return withdrawalQueue;
        }

        @Override
        public long countActiveWithdrawalQueue() {
            return activeQueueCount;
        }

        @Override
        public BigDecimal avgActiveWithdrawalQueueRiskScore() {
            return avgRiskScore;
        }

        @Override
        public BigDecimal sumPendingCommissionUsdt() {
            return pendingCommission;
        }

        @Override
        public BigDecimal sumNetUsdtFlowBetween(LocalDateTime startAt, LocalDateTime endAt) {
            return netFlow;
        }

        @Override
        public List<Map<String, Object>> maturityBuckets(LocalDateTime startAt, LocalDateTime endAt) {
            return List.of();
        }

        @Override
        public List<BigDecimal> riskPressureSeries(LocalDateTime since) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> riskRuleBuckets(LocalDateTime since) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> riskSeverityBuckets(LocalDateTime since) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> riskVolumeBuckets(LocalDateTime since) {
            return List.of();
        }

        @Override
        public BigDecimal currentReserveUsd() {
            return reserveUsd;
        }

        @Override
        public Optional<BigDecimal> latestNexUsdtPrice() {
            return Optional.ofNullable(nexUsdRate);
        }

        @Override
        public void recordReserveInjection(String voucherNo, BigDecimal amountUsd, String reason, String operator, String idempotencyKey) {
            BigDecimal normalized = amountUsd.setScale(2, java.math.RoundingMode.HALF_UP);
            reserveUsd = reserveUsd.add(normalized);
            reserveInjections.add(Map.of(
                    "voucherNo", voucherNo,
                    "amountUsd", normalized,
                    "reason", reason,
                    "operator", operator,
                    "idempotencyKey", idempotencyKey));
        }

        @Override
        public long countLedgerBills(String type, Long userId, String keyword) {
            lastBillType = type;
            lastBillUserId = userId;
            lastBillKeyword = keyword;
            return bills.size();
        }

        @Override
        public List<TreasuryLedgerBillView> pageLedgerBills(String type, Long userId, String keyword, int pageSize, int offset) {
            lastBillType = type;
            lastBillUserId = userId;
            lastBillKeyword = keyword;
            return bills.stream().skip(offset).limit(pageSize).toList();
        }

        @Override
        public List<TreasuryLedgerBillView> userLedgerRows(Long userId, int limit) {
            return bills.stream().filter(row -> row.userId().equals(userId)).limit(limit).toList();
        }

        @Override
        public Optional<BigDecimal> currentUserBalance(Long userId, String asset) {
            return bills.stream()
                    .filter(row -> row.userId().equals(userId) && row.asset().equals(asset))
                    .findFirst()
                    .map(TreasuryLedgerBillView::balanceAfter);
        }

        @Override
        public void createLedgerAdjustment(String adjustmentNo, Long userId, String asset, String direction,
                                           BigDecimal amount, String relatedBizNo, String reason, String operator) {
            adjustments.add(Map.of(
                    "adjustmentNo", adjustmentNo,
                    "userId", userId,
                    "asset", asset,
                    "direction", direction,
                    "amount", amount,
                    "relatedBizNo", relatedBizNo,
                    "reason", reason,
                    "operator", operator));
        }

        @Override
        public void postLedgerEntry(String bizNo, Long userId, String bizType, String asset, String direction,
                                    BigDecimal amount, String status, String remark) {
            bills.add(new TreasuryLedgerBillView(
                    (long) bills.size() + 1,
                    userId,
                    "U" + String.format("%08d", userId),
                    "账单用户",
                    bizNo,
                    bizType,
                    asset,
                    direction,
                    amount,
                    currentUserBalance(userId, asset).orElse(BigDecimal.ZERO).add(amount),
                    status,
                    remark,
                    LocalDateTime.now(CLOCK),
                    LocalDateTime.now(CLOCK)));
        }

    }
}
