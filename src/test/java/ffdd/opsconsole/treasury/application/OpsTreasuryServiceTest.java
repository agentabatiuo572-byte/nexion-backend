package ffdd.opsconsole.treasury.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerBillView;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsTreasuryServiceTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-17T00:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final FakeTreasuryLedgerRepository ledgerRepository = new FakeTreasuryLedgerRepository();
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsTreasuryService service = service();

    private OpsTreasuryService service() {
        OpsTreasuryService service = new OpsTreasuryService(
                ledgerRepository,
                configFacade,
                auditLogService,
                CLOCK,
                new TreasuryDualLedgerProperties());
        return service;
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
    void dualLedgerAggregatesLiabilitiesWithoutSunsetProducts() {
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
    void injectionUpdatesReserveConfigAndWritesAudit() {
        configFacade.values.put("wallet.dual-ledger.reserve-usd", "5000");
        TreasuryInjectionRequest request =
                new TreasuryInjectionRequest(new BigDecimal("250.129"), "V-20260617", "reserve top-up", "superadmin");

        ApiResult<Map<String, Object>> result = service.createInjection("idem-b1", request);

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("wallet.dual-ledger.reserve-usd", "5250.13");
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
    void ledgerBillsReturnsServerPage() {
        ledgerRepository.bills.add(new TreasuryLedgerBillView(
                1L,
                10001L,
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
        assertThat(ledgerRepository.lastBillType).isEqualTo("WITHDRAWAL");
        assertThat(ledgerRepository.lastBillUserId).isEqualTo(10001L);
        assertThat(ledgerRepository.lastBillKeyword).isEqualTo("WD");
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
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
        private final List<TreasuryLedgerBillView> bills = new ArrayList<>();
        private final List<Map<String, Object>> adjustments = new ArrayList<>();
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
    }
}
