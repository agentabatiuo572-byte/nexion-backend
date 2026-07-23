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
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.facade.RiskTamperSignalFacade;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerBillView;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import ffdd.opsconsole.treasury.dto.TreasuryAlertAckRequest;
import ffdd.opsconsole.treasury.dto.TreasuryForecastConfigRequest;
import ffdd.opsconsole.treasury.dto.TreasuryInjectionRequest;
import ffdd.opsconsole.treasury.dto.TreasuryLedgerQueryRequest;
import ffdd.opsconsole.treasury.dto.TreasuryScopeRequest;
import ffdd.opsconsole.treasury.dto.TreasuryThresholdRequest;
import ffdd.opsconsole.treasury.dto.BankRunThresholdRequest;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsTreasuryServiceTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-17T00:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final FakeTreasuryLedgerRepository ledgerRepository = new FakeTreasuryLedgerRepository();
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final FakeEmergencyControlRepository emergencyRepository = new FakeEmergencyControlRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final EventOutboxService eventOutboxService = mock(EventOutboxService.class);
    private final RiskTamperSignalFacade riskTamperSignalFacade = mock(RiskTamperSignalFacade.class);
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
                riskTamperSignalFacade,
                auditLogService,
                idempotencyService,
                eventOutboxService,
                CLOCK,
                new TreasuryDualLedgerProperties(),
                new ObjectMapper(),
                seedPolicy);
        when(riskTamperSignalFacade.tamperRadarSnapshot(any()))
                .thenReturn(RiskTamperSignalFacade.TamperRadarSnapshot.empty());
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
    void missingKillSwitchRowsStillExposeTheFiveCanonicalDefaultOnlineGates() {
        OpsTreasuryService realOnlyService = service(OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        ApiResult<Map<String, Object>> result = realOnlyService.bDomainDashboard();

        assertThat(result.getCode()).isZero();
        Map<String, Object> riskRadar = (Map<String, Object>) result.getData().get("riskRadar");
        assertThat((List<Map<String, Object>>) riskRadar.get("gates"))
                .hasSize(5)
                .allSatisfy(gate -> {
                    assertThat(gate).containsEntry("on", true);
                    assertThat(gate).containsEntry("state", "default_on");
                });
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
        ledgerRepository.legacyLockOther = new BigDecimal("250");
        ledgerRepository.netFlow = new BigDecimal("-20");

        Map<String, Object> dualLedger = service.dualLedger().getData();

        Map<String, Object> snapshot = (Map<String, Object>) dualLedger.get("snapshot");
        assertThat(snapshot)
                .containsEntry("reserveUsd", new BigDecimal("4500.00"))
                .containsEntry("liabilitiesUsd", new BigDecimal("2367.00"))
                .containsEntry("queueBacklogCount", 3L)
                .containsEntry("avgRiskScore", 42L);
        assertThat((Iterable<Map<String, Object>>) dualLedger.get("accounts"))
                .extracting(account -> account.get("key"))
                .containsExactly("withdrawable_balance", "usdt_staking_principal", "staking_interest",
                        "genesis_daily_emission", "nex_v2_future", "withdrawal_queue", "commission_cooling", "lock_other")
                .doesNotContain("nex_payable", "pending_withdraw", "premium", "points");
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
                .containsExactly("withdraw", "exchange", "staking", "genesis", "trial");
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
        ledgerRepository.usdtAvailable = new BigDecimal("6000");
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
        ledgerRepository.usdtAvailable = new BigDecimal("6000");
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
        ledgerRepository.usdtAvailable = new BigDecimal("6000");
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

        assertThatThrownBy(() -> service.createInjection(" ", request))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));
        assertThatThrownBy(() -> service.createInjection("idem-1", new TreasuryInjectionRequest(new BigDecimal("100"), "V-1", " ", "superadmin")))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));
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
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("D3_TREASURY_RESERVE_INJECTION");
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
        verify(auditLogService, times(1)).recordRequired(any(AuditLogWriteRequest.class));
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
        verify(auditLogService, times(1)).recordRequired(any(AuditLogWriteRequest.class));
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
    void scopeUpdateReplaysSameIdempotencyKeyWithoutDuplicateConfigOrAudit() {
        TreasuryScopeRequest request = new TreasuryScopeRequest("active liabilities only", "policy change", "superadmin");

        service.updateScope("idem-scope-replay", request);
        service.updateScope("idem-scope-replay", request);

        assertThat(configFacade.upsertedKeys.stream()
                .filter("wallet.dual-ledger.scope"::equals)
                .count()).isEqualTo(1);
        verify(auditLogService, times(1)).record(any(AuditLogWriteRequest.class));
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
    void thresholdUpdateReplaysSameIdempotencyKeyWithoutDuplicateConfigOrAudit() {
        TreasuryThresholdRequest request = new TreasuryThresholdRequest(
                new BigDecimal("92.36"), new BigDecimal("112.4"), null, "risk policy", "superadmin");

        service.updateThresholds("idem-threshold-replay", request);
        service.updateThresholds("idem-threshold-replay", request);

        assertThat(configFacade.upsertedKeys.stream()
                .filter(key -> key.equals("wallet.dual-ledger.redline-pct")
                        || key.equals("wallet.dual-ledger.healthy-pct"))
                .count()).isEqualTo(2);
        verify(auditLogService, times(1)).record(any(AuditLogWriteRequest.class));
    }

    @Test
    void thresholdUpdateEnforcesThePrdRedAndYellowRanges() {
        assertThatThrownBy(() -> service.updateThresholds(
                "idem-threshold-red-low",
                new TreasuryThresholdRequest(new BigDecimal("79.9"), new BigDecimal("110"), null,
                        "risk policy", "superadmin")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("redlinePct is out of range");
        assertThatThrownBy(() -> service.updateThresholds(
                "idem-threshold-red-high",
                new TreasuryThresholdRequest(new BigDecimal("150.1"), new BigDecimal("160"), null,
                        "risk policy", "superadmin")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("redlinePct is out of range");
        assertThatThrownBy(() -> service.updateThresholds(
                "idem-threshold-yellow-low",
                new TreasuryThresholdRequest(new BigDecimal("90"), new BigDecimal("99.9"), null,
                        "risk policy", "superadmin")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("healthyPct is out of range");
        assertThatThrownBy(() -> service.updateThresholds(
                "idem-threshold-yellow-high",
                new TreasuryThresholdRequest(new BigDecimal("120"), new BigDecimal("200.1"), null,
                        "risk policy", "superadmin")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("healthyPct is out of range");
        assertThat(configFacade.upsertedKeys)
                .doesNotContain("wallet.dual-ledger.redline-pct", "wallet.dual-ledger.healthy-pct");
        verifyNoInteractions(auditLogService);
    }

    @Test
    void thresholdUpdateAcceptsBothInclusivePrdBoundaries() {
        ApiResult<Map<String, Object>> minimums = service.updateThresholds(
                "idem-threshold-minimums",
                new TreasuryThresholdRequest(new BigDecimal("80"), new BigDecimal("100"), null,
                        "risk policy", "superadmin"));
        ApiResult<Map<String, Object>> maximums = service.updateThresholds(
                "idem-threshold-maximums",
                new TreasuryThresholdRequest(new BigDecimal("150"), new BigDecimal("200"), null,
                        "risk policy", "superadmin"));

        assertThat(minimums.getCode()).isZero();
        assertThat(maximums.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("wallet.dual-ledger.redline-pct", "150.0")
                .containsEntry("wallet.dual-ledger.healthy-pct", "200.0");
        verify(auditLogService, times(2)).record(any(AuditLogWriteRequest.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void d3CanonicalReadModelsExposeReserveEightLiabilitiesAndWindowedForecastsWithoutCoverage() {
        ledgerRepository.usdtAvailable = new BigDecimal("1000");
        ledgerRepository.stakingPrincipal = new BigDecimal("500");
        ledgerRepository.stakingInterest = new BigDecimal("50");
        ledgerRepository.nexLocked = new BigDecimal("100");
        ledgerRepository.nexReward = new BigDecimal("25");
        ledgerRepository.withdrawalQueue = new BigDecimal("300");
        ledgerRepository.pendingCommission = new BigDecimal("80");
        ledgerRepository.pendingWithdraw = new BigDecimal("40");
        ledgerRepository.injectedCumulative = new BigDecimal("250");
        ledgerRepository.genesisDaily = new BigDecimal("12.50");

        Map<String, Object> reserve = service.reserve().getData();
        Map<String, Object> liabilities = service.liabilities(true).getData();
        Map<String, Object> maturity = service.maturityForecast("30d").getData();
        Map<String, Object> exposure = service.netExposure("90d").getData();

        assertThat(reserve)
                .containsKeys("usdtReserveUsdt", "otherLiquidUsdt", "injectedCumulativeUsdt", "reserveTotalUsdt", "asOf", "waterLevel")
                .doesNotContainKeys("coverageRatio", "redlinePct", "healthyPct");
        assertThat((List<Map<String, Object>>) liabilities.get("breakdown"))
                .extracting(row -> row.get("category"))
                .containsExactly("withdrawable_balance", "usdt_staking_principal", "staking_interest", "genesis_daily_emission",
                        "nex_v2_future", "withdrawal_queue", "commission_cooling", "lock_other");
        assertThat((List<Map<String, Object>>) maturity.get("daily")).hasSize(30)
                .allSatisfy(row -> assertThat(row).containsKeys("date", "withdrawDueUsdt", "interestDueUsdt", "genesisDividendUsdt"));
        assertThat(maturity).containsKeys("cumulative", "reserveCoverDays", "farLiabilityExcluded");
        assertThat((List<Map<String, Object>>) exposure.get("series")).hasSize(90);
        assertThat(exposure).doesNotContainKey("coverageRatio");
    }

    @Test
    @SuppressWarnings("unchecked")
    void d3ForecastConfigIsStructuredVersionedAndReasonBounded() {
        TreasuryForecastConfigRequest request = new TreasuryForecastConfigRequest(
                Map.of("usdt", true, "otherLiquid", false),
                Map.of(
                        "withdrawable_balance", true,
                        "usdt_staking_principal", true,
                        "staking_interest", true,
                        "genesis_daily_emission", true,
                        "nex_v2_future", true,
                        "withdrawal_queue", true,
                        "commission_cooling", true,
                        "lock_other", true),
                "30d", true, false, "LINEAR", false,
                0L, "调整未来三十天资金预测口径", "finance-lead");

        assertThatThrownBy(() -> service.updateForecastConfig("idem-config-short", new TreasuryForecastConfigRequest(
                null, null, "7d", true, false, "LINEAR", false, 0L, "太短", "finance-lead")))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));

        Map<String, Object> response = service.updateForecastConfig("idem-config-1", request).getData();

        assertThat(response).containsKeys("before", "after", "forecastDeltaPreview", "effectiveAt", "version");
        assertThat(response).containsEntry("effectiveAt", Instant.parse("2026-06-18T00:00:00Z"));
        assertThat((Map<String, Object>) response.get("after"))
                .containsEntry("forecastWindow", "30d")
                .containsEntry("stakingInterestMode", "LINEAR");
        assertThat(configFacade.values)
                .doesNotContainKey("treasury.d3.forecast-config")
                .containsKey("treasury.d3.forecast-config.pending")
                .containsEntry("treasury.d3.forecast-config.pending-effective-at", "2026-06-18T00:00:00Z");
        assertThat(service.forecastConfig().getData())
                .containsEntry("forecastWindow", "7d")
                .containsEntry("pendingEffectiveAt", Instant.parse("2026-06-18T00:00:00Z"));
        verify(auditLogService).recordRequired(any(AuditLogWriteRequest.class));
        verify(eventOutboxService).publish(eq("TREASURY_CONFIG"), eq("D3"), eq("admin.treasury_forecast_config_changed"), any());
    }

    @Test
    void d3ForecastConfigRejectsStaleVersionBeforeCreatingANewPendingRevision() {
        configFacade.values.put("treasury.d3.forecast-config.version", "4");
        TreasuryForecastConfigRequest stale = new TreasuryForecastConfigRequest(
                null, null, "30d", true, false, "AT_MATURITY", true,
                3L, "并发修改时必须拒绝旧版本请求", "finance-lead");

        assertThatThrownBy(() -> service.updateForecastConfig("idem-config-stale", stale))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(409));

        assertThat(configFacade.values).doesNotContainKey("treasury.d3.forecast-config.pending");
        verifyNoInteractions(auditLogService);
    }

    @Test
    void bDomainAlertAckRejectsHealthyCoverageWithoutWritingPseudoDisposition() {
        TreasuryAlertAckRequest request = new TreasuryAlertAckRequest("no active alert to acknowledge", "superadmin");

        ApiResult<Map<String, Object>> result =
                service.acknowledgeBDomainAlert("coverage-redline", "idem-b-alert-healthy", request);

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("B_ALERT_NOT_ACTIVE");
        assertThat(configFacade.values).doesNotContainKey("wallet.dual-ledger.alert.coverage-redline.ack");
        verifyNoInteractions(auditLogService);
    }

    @Test
    void d3DuePendingConfigIsPromotedClearedAndSnapshottedExactlyOnce() throws Exception {
        String pending = new ObjectMapper().writeValueAsString(Map.of(
                "reserveCategories", Map.of("usdt", true, "otherLiquid", false),
                "liabilityCategories", Map.of(
                        "withdrawable_balance", true, "usdt_staking_principal", true,
                        "staking_interest", true, "genesis_daily_emission", true,
                        "nex_v2_future", true, "withdrawal_queue", true,
                        "commission_cooling", true, "lock_other", true),
                "forecastWindow", "30d", "genesisIncluded", true,
                "includeFarLiabilities", false, "stakingInterestMode", "AT_MATURITY",
                "trialStressEnabled", true));
        configFacade.values.put("treasury.d3.forecast-config.version", "5");
        configFacade.values.put("treasury.d3.forecast-config.active-version", "4");
        configFacade.values.put("treasury.d3.forecast-config.pending-version", "5");
        configFacade.values.put("treasury.d3.forecast-config.pending", pending);
        configFacade.values.put("treasury.d3.forecast-config.pending-effective-at", "2026-06-16T00:00:00Z");

        Map<String, Object> response = service.forecastConfig().getData();

        assertThat(response)
                .containsEntry("forecastWindow", "30d")
                .containsEntry("version", 5L)
                .containsEntry("effectiveVersion", 5L)
                .doesNotContainKeys("pendingConfig", "pendingEffectiveAt", "pendingVersion");
        assertThat(configFacade.values)
                .containsEntry("treasury.d3.forecast-config.pending", "")
                .containsEntry("treasury.d3.forecast-config.pending-effective-at", "")
                .containsEntry("treasury.d3.forecast-config.pending-version", "")
                .containsKey("treasury.d3.forecast-config.snapshot.5");
    }

    @Test
    void d3InjectionRejectsMissingShortOrDuplicateVoucherWithoutSideEffects() {
        BigDecimal before = ledgerRepository.reserveUsd;

        assertThatThrownBy(() -> service.createInjection("idem-empty", new TreasuryInjectionRequest(
                new BigDecimal("10"), " ", "登记真实到账凭证并纳入储备", "finance-lead")))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));
        assertThatThrownBy(() -> service.createInjection("idem-short", new TreasuryInjectionRequest(
                new BigDecimal("10"), "BANK-20260720-001", "短", "finance-lead")))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));
        ledgerRepository.vouchers.add("BANK-20260720-001");
        assertThatThrownBy(() -> service.createInjection("idem-duplicate", new TreasuryInjectionRequest(
                new BigDecimal("10"), "BANK-20260720-001", "登记真实到账凭证并纳入储备", "finance-lead")))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(409));

        assertThat(ledgerRepository.reserveUsd).isEqualByComparingTo(before);
        assertThat(ledgerRepository.reserveInjections).isEmpty();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void b5BankRunThresholdUpdateBecomesTheJ1R1Authority() {
        BankRunThresholdRequest request = new BankRunThresholdRequest(
                "25", "55", "调整挤兑分层阈值并同步自动止血线", "risk-lead");

        ApiResult<Map<String, Object>> result = service.updateBankRunThresholds(
                "idem-bankrun-threshold", request);
        ApiResult<Map<String, Object>> replay = service.updateBankRunThresholds(
                "idem-bankrun-threshold", request);

        assertThat(result.getCode()).isZero();
        assertThat(replay.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("risk.bankrun-yellow-pct", "25")
                .containsEntry("risk.bankrun-red-pct", "55");
        @SuppressWarnings("unchecked")
        Map<String, Object> riskRadar = (Map<String, Object>) result.getData().get("riskRadar");
        assertThat(riskRadar)
                .containsEntry("bankRunYellowPct", new BigDecimal("25"))
                .containsEntry("bankRunRedlinePct", new BigDecimal("55"));
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("B5_BANKRUN_THRESHOLDS_CHANGED");
    }

    @Test
    void b5BankRunThresholdAuditUsesAuthenticatedActorInsteadOfRequestSpoof() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(77L, null, List.of());
        authentication.setDetails(Map.of("username", "real-risk-admin"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            ApiResult<Map<String, Object>> result = service.updateBankRunThresholds(
                    "idem-bankrun-authenticated-actor",
                    new BankRunThresholdRequest(
                            "24", "52", "调整分层阈值并验证真实审计操作者", "spoofed-operator"));

            assertThat(result.getCode()).isZero();
            ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
            verify(auditLogService).record(captor.capture());
            assertThat(captor.getValue().getActorUsername()).isEqualTo("real-risk-admin");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void b5RiskLightAndFeedUseTheSameRequestedAmountRatioAndConfiguredBandsAsJ1R1() {
        ledgerRepository.reserveUsd = new BigDecimal("1000");
        ledgerRepository.withdrawalRequested24h = new BigDecimal("300");
        ledgerRepository.withdrawalQueue = new BigDecimal("600");
        configFacade.values.put("risk.bankrun-yellow-pct", "25");
        configFacade.values.put("risk.bankrun-red-pct", "55");

        Map<String, Object> riskRadar = (Map<String, Object>) service.bDomainDashboard().getData().get("riskRadar");
        List<Map<String, Object>> feed = (List<Map<String, Object>>) riskRadar.get("feed");

        assertThat((BigDecimal) riskRadar.get("bankRunRatio")).isEqualByComparingTo("30");
        assertThat(riskRadar)
                .containsEntry("bankRunYellowPct", new BigDecimal("25"))
                .containsEntry("bankRunRedlinePct", new BigDecimal("55"));
        assertThat(feed.get(0))
                .containsEntry("sev", "p1")
                .satisfies(row -> assertThat(row.get("t").toString()).contains("挤兑比率 30%"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void b5RiskRadarReadsTheActualSharedTamperSignalProjection() {
        when(riskTamperSignalFacade.tamperRadarSnapshot(any()))
                .thenReturn(new RiskTamperSignalFacade.TamperRadarSnapshot(
                        4, 2, "2026-06-17T07:55:00"));

        Map<String, Object> riskRadar =
                (Map<String, Object>) service.bDomainDashboard().getData().get("riskRadar");
        List<Map<String, Object>> feed = (List<Map<String, Object>>) riskRadar.get("feed");

        assertThat(feed).anySatisfy(row -> assertThat(row)
                .containsEntry("sev", "p1")
                .containsEntry("href", "/emergency/tamper")
                .satisfies(item -> assertThat(item.get("t").toString())
                        .contains("篡改拦截 4 起", "涉及 2 个账户")));
        assertThat((List<String>) riskRadar.get("sources"))
                .contains("nx_risk_signal:TAMPER_DETECTED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void b5RiskRadarUsesOnlyCurrentK4EffectiveScoresAndKeepsDecisionEvidenceSeparate() {
        ledgerRepository.k4RiskScoreSnapshot = Map.ofEntries(
                Map.entry("modelVersion", "k4-v23"),
                Map.entry("totalUsers", 6L),
                Map.entry("bandLowMax", 35),
                Map.entry("bandHighMin", 65),
                Map.entry("autoEscalateScore", 80),
                Map.entry("autoEscalated", 1L),
                Map.entry("highRisk", 1L),
                Map.entry("mediumRisk", 2L),
                Map.entry("lowRisk", 3L),
                Map.entry("flaggedAccounts", 3L),
                Map.entry("activeOverrides", 1L),
                Map.entry("staleScoreUsers", 1L));
        ledgerRepository.riskSeverityRows = List.of(
                Map.of("nm", "P1", "v", 9L, "c", "var(--warning)"));

        Map<String, Object> riskRadar =
                (Map<String, Object>) service.bDomainDashboard().getData().get("riskRadar");

        assertThat(riskRadar)
                .containsEntry("k4ScoringAvailable", true)
                .containsEntry("k4ModelVersion", "k4-v23")
                .containsEntry("k4BandLowMax", 35)
                .containsEntry("k4BandHighMin", 65)
                .containsEntry("k4AutoEscalateScore", 80)
                .containsEntry("k4AutoEscalatedAccounts", 1L)
                .containsEntry("flaggedAccounts", 3L)
                .containsEntry("k4ActiveOverrides", 1L)
                .containsEntry("k4StaleScoreUsers", 1L);
        assertThat((List<Map<String, Object>>) riskRadar.get("severity"))
                .extracting(row -> row.get("nm") + ":" + row.get("v"))
                .containsExactly("高风险:1", "中风险:2", "低风险:3");
        assertThat((List<Map<String, Object>>) riskRadar.get("decisionSeverity"))
                .extracting(row -> row.get("nm") + ":" + row.get("v"))
                .containsExactly("P1:9");
        assertThat((List<Map<String, Object>>) riskRadar.get("feed"))
                .anySatisfy(row -> assertThat(row)
                        .containsEntry("href", "/risk/scoring")
                        .containsEntry("sev", "p0")
                        .satisfies(item -> assertThat(item.get("t").toString())
                                .contains("自动升级线 80", "1 个账户", "k4-v23")))
                .anySatisfy(row -> assertThat(row.get("t").toString()).contains("1 个账户等待当前模型重算"));
        assertThat((List<String>) riskRadar.get("sources"))
                .contains("nx_admin_risk_score_user:current-model",
                        "nx_admin_risk_score_user:fresh-current-model",
                        "nx_admin_risk_score_model:active-thresholds",
                        "nx_admin_risk_score_override:active",
                        "nx_risk_decision");
    }

    @Test
    @SuppressWarnings("unchecked")
    void b5RiskRadarFailsClosedWhenTheK4ActiveSnapshotIsUnavailable() {
        ledgerRepository.k4RiskScoreSnapshot = Map.of();

        Map<String, Object> riskRadar =
                (Map<String, Object>) service.bDomainDashboard().getData().get("riskRadar");

        assertThat(riskRadar)
                .containsEntry("k4ScoringAvailable", false)
                .containsEntry("flaggedAccounts", 0L);
        assertThat((List<Map<String, Object>>) riskRadar.get("severity")).isEmpty();
        assertThat((List<Map<String, Object>>) riskRadar.get("feed"))
                .anySatisfy(row -> assertThat(row)
                        .containsEntry("sev", "p1")
                        .containsEntry("href", "/risk/scoring")
                        .satisfies(item -> assertThat(item.get("t").toString()).contains("停止推测")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void b5RiskRadarProjectsRecentK5ThresholdSlaAndBurstAlertsInStableOrderAndLimit() {
        ledgerRepository.k5KycAlerts = List.of(
                k5Alert("threshold-hit:T-6", "warn", "KYC 复审追加触发 · T-6", "阈值再次命中", "2026-06-17 08:06", 0),
                k5Alert("sla-breach:T-5", "bad", "KYC 复审 SLA 逾期 · T-5", "已超过处理期限", "2026-06-17 08:05", 0),
                k5Alert("large-withdraw-burst:202606170804", "bad", "大额提现集中触发 KYC 复审", "最近一小时达到集中阈值", "2026-06-17 08:04", 0),
                k5Alert("threshold-hit:T-3", "warn", "KYC 复审已触发 · T-3", "评分阈值命中", "2026-06-17 08:03", 0),
                k5Alert("sla-breach:T-2", "bad", "KYC 复审 SLA 逾期 · T-2", "已超过处理期限", "2026-06-17 08:02", 0),
                k5Alert("threshold-hit:T-1", "warn", "KYC 复审已触发 · T-1", "评分阈值命中", "2026-06-17 08:01", 0));

        Map<String, Object> riskRadar =
                (Map<String, Object>) service.bDomainDashboard().getData().get("riskRadar");
        List<Map<String, Object>> k5Feed = ((List<Map<String, Object>>) riskRadar.get("feed")).stream()
                .filter(row -> "K5".equals(row.get("domain")))
                .toList();

        assertThat(k5Feed).hasSize(5);
        assertThat(k5Feed).extracting(row -> row.get("eventKey"))
                .containsExactly("threshold-hit:T-6", "sla-breach:T-5", "large-withdraw-burst:202606170804",
                        "threshold-hit:T-3", "sla-breach:T-2");
        assertThat(k5Feed).allSatisfy(row -> assertThat(row)
                .containsEntry("domain", "K5")
                .containsEntry("href", "/risk/kyc-review")
                .containsEntry("route", "/risk/kyc-review")
                .containsKeys("sev", "severityLabel", "t", "m"));
        assertThat(k5Feed.get(0)).containsEntry("sev", "p2").containsEntry("severityLabel", "中风险");
        assertThat(k5Feed.get(1)).containsEntry("sev", "p1").containsEntry("severityLabel", "高风险");
        assertThat((List<String>) riskRadar.get("sources")).contains("nx_admin_risk_kyc_alert:active-recent");
    }

    @Test
    @SuppressWarnings("unchecked")
    void b5RiskRadarKeepsK5ProjectionEmptyWhenNoActiveRecentAlertExists() {
        ledgerRepository.k5KycAlerts = List.of();

        Map<String, Object> riskRadar =
                (Map<String, Object>) service.bDomainDashboard().getData().get("riskRadar");

        assertThat((List<Map<String, Object>>) riskRadar.get("feed"))
                .noneSatisfy(row -> assertThat(row).containsEntry("domain", "K5"));
        assertThat((List<String>) riskRadar.get("sources")).contains("nx_admin_risk_kyc_alert:active-recent");
    }

    @Test
    @SuppressWarnings("unchecked")
    void b5RiskRadarNeverProjectsDeletedOrUnsupportedK5Alerts() {
        ledgerRepository.k5KycAlerts = List.of(
                k5Alert("threshold-hit:T-DELETED", "warn", "已删除告警", "不应展示", "2026-06-17 08:00", 1),
                k5Alert("manual-note:T-OTHER", "bad", "非三类告警", "不应展示", "2026-06-17 07:59", 0));

        Map<String, Object> riskRadar =
                (Map<String, Object>) service.bDomainDashboard().getData().get("riskRadar");

        assertThat((List<Map<String, Object>>) riskRadar.get("feed"))
                .noneSatisfy(row -> assertThat(row).containsEntry("domain", "K5"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void b5RiskRadarFallsBackToTheSameSafeBandsAsJ1WhenStoredValuesAreAbnormal() {
        configFacade.values.put("risk.bankrun-yellow-pct", "50");
        configFacade.values.put("risk.bankrun-red-pct", "45");

        Map<String, Object> riskRadar =
                (Map<String, Object>) service.bDomainDashboard().getData().get("riskRadar");

        assertThat(riskRadar)
                .containsEntry("bankRunYellowPct", new BigDecimal("20"))
                .containsEntry("bankRunRedlinePct", new BigDecimal("40"));
    }

    @Test
    void thresholdUpdateRejectsEqualRedlineAndHealthy() {
        TreasuryThresholdRequest request = new TreasuryThresholdRequest(
                new BigDecimal("110"), new BigDecimal("110"), null, "risk policy", "superadmin");

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
        assertThat(ledgerRepository.lastBillType).isEqualTo("withdraw");
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
    void ledgerBillsCsvRejectsShortReasonBeforeReadingOrAuditing() {
        assertThatThrownBy(() -> service.ledgerBillsCsv(
                new TreasuryLedgerQueryRequest(null, null, null, 1, 20), "short"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("D4_EXPORT_REASON_LENGTH_INVALID");

        assertThat(ledgerRepository.lastBillType).isNull();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void ledgerBillsCsvMasksUserAndWritesRequiredUnifiedExportAudit() {
        ledgerRepository.bills.add(new TreasuryLedgerBillView(
                1L, 10001L, "U00010001", "不应出现在导出中的昵称", "WD-1", "WITHDRAWAL", "USDT", "OUT",
                new BigDecimal("25.5"), new BigDecimal("74.5"), "SUCCESS", "withdraw completed",
                LocalDateTime.now(CLOCK), LocalDateTime.now(CLOCK)));

        String csv = new String(service.ledgerBillsCsv(
                new TreasuryLedgerQueryRequest(null, null, null, 1, 20),
                "99105 L5验收七类账单脱敏导出"), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(csv)
                .contains("bill_id,user_masked,bill_type")
                .contains("U******01")
                .doesNotContain("U00010001", "不应出现在导出中的昵称");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("admin.report_exported");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("exportType", "BILL_CSV")
                .containsEntry("containsPii", true)
                .containsEntry("maskingPolicy", "MASKED")
                .containsEntry("rowCount", 1)
                .containsEntry("reason", "99105 L5验收七类账单脱敏导出");
    }

    @Test
    @SuppressWarnings("unchecked")
    void d4RunningBalanceDetectsInternalBreakAndReconcilesAgainstWalletTruth() {
        ledgerRepository.bills.add(new TreasuryLedgerBillView(
                1L, 10001L, "U00010001", "测试用户", "TOPUP-1", "CARD_TOPUP", "USDT", "IN",
                new BigDecimal("100"), new BigDecimal("100"), "POSTED", "topup",
                LocalDateTime.parse("2026-06-15T10:00:00"), LocalDateTime.parse("2026-06-15T10:00:00")));
        ledgerRepository.bills.add(new TreasuryLedgerBillView(
                2L, 10001L, "U00010001", "测试用户", "WD-1", "WITHDRAWAL", "USDT", "OUT",
                new BigDecimal("20"), new BigDecimal("79"), "POSTED", "withdraw",
                LocalDateTime.parse("2026-06-16T10:00:00"), LocalDateTime.parse("2026-06-16T10:00:00")));
        ledgerRepository.actualBalances.put("10001:USDT", new BigDecimal("80"));

        ApiResult<Map<String, Object>> result = service.runningBalance(10001L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("breakCount", 2);
        Map<String, Object> reconciliation = (Map<String, Object>) result.getData().get("reconciliation");
        assertThat(reconciliation).containsEntry("USDT", new BigDecimal("1"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.getData().get("rows");
        assertThat(rows.get(1)).containsEntry("breakDetected", true)
                .containsEntry("expectedBalanceAfter", new BigDecimal("80"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void d4UserLedgerAlwaysReturnsAllSevenBillTypesForBothCanonicalAssets() {
        ledgerRepository.bills.add(new TreasuryLedgerBillView(
                1L, 10001L, "U00010001", "测试用户", "TOPUP-1", "CARD_TOPUP", "USDT", "IN",
                new BigDecimal("100"), new BigDecimal("100"), "POSTED", "topup",
                LocalDateTime.parse("2026-06-15T10:00:00"), LocalDateTime.parse("2026-06-15T10:00:00")));

        ApiResult<Map<String, Object>> result = service.userLedger(10001L);

        assertThat(result.getCode()).isZero();
        Map<String, BigDecimal> categorySums =
                (Map<String, BigDecimal>) result.getData().get("categorySums");
        assertThat(categorySums).hasSize(14)
                .containsEntry("swap:USDT", BigDecimal.ZERO)
                .containsEntry("swap:NEX", BigDecimal.ZERO)
                .containsEntry("topup:USDT", new BigDecimal("100"))
                .containsEntry("topup:NEX", BigDecimal.ZERO)
                .containsEntry("withdraw:USDT", BigDecimal.ZERO)
                .containsEntry("earning:USDT", BigDecimal.ZERO)
                .containsEntry("commission:USDT", BigDecimal.ZERO)
                .containsEntry("refund:USDT", BigDecimal.ZERO)
                .containsEntry("bonus:USDT", BigDecimal.ZERO);
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
        public boolean claimExecutionRollback(String executionId) {
            return false;
        }

        @Override
        public boolean completeExecutionRollback(String executionId, LocalDateTime rollbackAt, String reason,
                                                 List<Map<String, Object>> rollbackActions) {
            return false;
        }
    }

    private static final class FakeTreasuryLedgerRepository implements TreasuryLedgerRepository {
        private long countValue;
        private long activeQueueCount;
        private BigDecimal usdtAvailable = BigDecimal.ZERO;
        private BigDecimal pendingWithdraw = BigDecimal.ZERO;
        private BigDecimal nexAvailable = BigDecimal.ZERO;
        private BigDecimal legacyLockOther = BigDecimal.ZERO;
        private BigDecimal stakingPrincipal = BigDecimal.ZERO;
        private BigDecimal stakingInterest = BigDecimal.ZERO;
        private BigDecimal nexLocked = BigDecimal.ZERO;
        private BigDecimal nexReward = BigDecimal.ZERO;
        private BigDecimal withdrawalQueue = BigDecimal.ZERO;
        private BigDecimal withdrawalRequested24h = BigDecimal.ZERO;
        private BigDecimal avgRiskScore = BigDecimal.ZERO;
        private BigDecimal pendingCommission = BigDecimal.ZERO;
        private BigDecimal netFlow = BigDecimal.ZERO;
        private BigDecimal reserveUsd = new BigDecimal("5000");
        private BigDecimal injectedCumulative = BigDecimal.ZERO;
        private BigDecimal genesisDaily = BigDecimal.ZERO;
        private BigDecimal nexUsdRate = new BigDecimal("0.17");
        private final List<TreasuryLedgerBillView> bills = new ArrayList<>();
        private final Map<String, BigDecimal> actualBalances = new LinkedHashMap<>();
        private final List<Map<String, Object>> reserveInjections = new ArrayList<>();
        private final java.util.Set<String> vouchers = new java.util.HashSet<>();
        private String lastBillType;
        private Long lastBillUserId;
        private String lastBillKeyword;
        private Map<String, Object> k4RiskScoreSnapshot = Map.of();
        private List<Map<String, Object>> riskSeverityRows = List.of();
        private List<Map<String, Object>> k5KycAlerts = List.of();

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
        public BigDecimal legacyLockOtherLiabilityUsd() {
            return legacyLockOther;
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
        public BigDecimal sumWithdrawalRequested24hUsdt() {
            return withdrawalRequested24h;
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
            return riskSeverityRows;
        }

        @Override
        public Map<String, Object> currentK4RiskScoreSnapshot() {
            return k4RiskScoreSnapshot;
        }

        @Override
        public List<Map<String, Object>> recentK5KycAlerts(LocalDateTime since, int limit) {
            return k5KycAlerts.stream().limit(limit).toList();
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
        public BigDecimal injectedCumulativeUsd() {
            return injectedCumulative;
        }

        @Override
        public BigDecimal genesisDailyLiabilityUsd() {
            return genesisDaily;
        }

        @Override
        public boolean reserveVoucherExists(String voucherNo) {
            return vouchers.contains(voucherNo);
        }

        @Override
        public Optional<BigDecimal> latestNexUsdtPrice() {
            return Optional.ofNullable(nexUsdRate);
        }

        @Override
        public void recordReserveInjection(String voucherNo, BigDecimal amountUsd, String reason, String operator, String idempotencyKey) {
            BigDecimal normalized = amountUsd.setScale(2, java.math.RoundingMode.HALF_UP);
            reserveUsd = reserveUsd.add(normalized);
            injectedCumulative = injectedCumulative.add(normalized);
            vouchers.add(voucherNo);
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
        public Optional<BigDecimal> actualUserBalance(Long userId, String asset) {
            return Optional.ofNullable(actualBalances.get(userId + ":" + asset))
                    .or(() -> currentUserBalance(userId, asset));
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

    private static Map<String, Object> k5Alert(
            String eventKey, String tone, String title, String body, String timeText, int isDeleted) {
        return Map.of(
                "eventKey", eventKey,
                "tone", tone,
                "title", title,
                "body", body,
                "timeText", timeText,
                "isDeleted", isDeleted);
    }
}
