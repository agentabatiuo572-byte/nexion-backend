package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.bi.domain.BiReportDownloadFile;
import ffdd.opsconsole.bi.domain.BiReportRepository;
import ffdd.opsconsole.bi.domain.BiReportView;
import ffdd.opsconsole.bi.domain.BiReportCreateCommand;
import ffdd.opsconsole.bi.dto.BiDashboardValueRequest;
import ffdd.opsconsole.bi.dto.BiReportActionRequest;
import ffdd.opsconsole.bi.dto.BiReportCreateRequest;
import ffdd.opsconsole.bi.dto.BiReportQueryRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerBillView;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsBiServiceTest {
    private final FakeBiReportRepository reportRepository = new FakeBiReportRepository();
    private final FakeTreasuryLedgerRepository ledgerRepository = new FakeTreasuryLedgerRepository();
    private GrowthRhythmSnapshot h1Snapshot = h1Snapshot(12, 7, "P3");
    private final GrowthRhythmFacade growthRhythmFacade = () -> h1Snapshot;
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsBiService service = new OpsBiService(reportRepository, growthRhythmFacade, ledgerRepository, auditLogService);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void overviewDeclaresSensitiveExportRules() {
        ApiResult<Map<String, Object>> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("securityParams").toString())
                .contains("confirm-with-reason")
                .contains("24 hours")
                .contains("decrypted PII export is blocked");
        assertThat(result.getData())
                .containsKeys("currentPhase", "phases", "l1", "l2", "l3", "l4", "l5", "l6");
        assertThat(result.getData().get("currentPhase"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("sourceDomain", "H1")
                .containsEntry("month", 7);
        assertThat(result.getData().get("l1")).isEqualTo(Map.of());
        assertThat(result.getData().get("l3")).isEqualTo(Map.of());
        assertThat(result.getData().get("l5").toString()).contains("nx_admin_fourth_batch_report");
        assertThat(result.getData().get("l6").toString()).contains("window=7d");
    }

    @Test
    void overviewReadsCurrentPhaseFromH1RhythmFacade() {
        h1Snapshot = h1Snapshot(12, 11, "P6");

        ApiResult<Map<String, Object>> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("currentPhase"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("code", "P6")
                .containsEntry("month", 11)
                .containsEntry("sourceDomain", "H1");
    }

    @Test
    void moduleOverviewsDoNotFabricateDashboardPayloads() {
        ApiResult<Map<String, Object>> l1 = service.kpiOverview();
        ApiResult<Map<String, Object>> l2 = service.funnelOverview();
        ApiResult<Map<String, Object>> l3 = service.financeOverview();
        ApiResult<Map<String, Object>> l4 = service.operationsOverview();
        ApiResult<Map<String, Object>> l5 = service.exportOverview();
        ApiResult<Map<String, Object>> l6 = service.behaviorHeatmapOverview("7d");

        assertThat(l1.getData()).isEmpty();
        assertThat(l2.getData()).isEmpty();
        assertThat(l3.getData()).containsKey("ledgerLive");
        assertThat(l4.getData()).isEmpty();
        assertThat(l5.getData()).containsKeys("summary", "ledgerLive", "reports", "sources");
        assertThat(l5.getData().get("sources").toString()).doesNotContain("nx_admin_bi_dashboard_payload");
        assertThat(l6.getData()).containsEntry("window", "7d");
        assertThat(l6.getData().get("sources").toString()).contains("nx_user", "nx_audit_log");
    }

    @Test
    void financeOverviewReadsLiveD4LedgerCounts() {
        ledgerRepository.counts.put(null, 12L);
        ledgerRepository.counts.put("REFUND", 2L);
        ledgerRepository.counts.put("TEAM_COMMISSION", 3L);
        ledgerRepository.counts.put("GENESIS_DIVIDEND", 1L);
        ledgerRepository.counts.put("EARNING", 4L);

        ApiResult<Map<String, Object>> result = service.financeOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("ledgerLive"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("totalBills", 12L)
                .containsEntry("refundBills", 2L)
                .containsEntry("teamCommissionBills", 3L)
                .containsEntry("genesisDividendBills", 1L)
                .containsEntry("earningBills", 4L);
        assertThat(result.getData().get("sources").toString()).contains("nx_wallet_ledger");
    }

    @Test
    void reportsUsePageResultAndStatusBuckets() {
        ApiResult<PageResult<BiReportView>> result = service.reports(new BiReportQueryRequest(
                null,
                "PENDING_CONFIRM,PENDING_SPLIT_CONFIRM",
                2,
                5,
                null));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getPageNum()).isEqualTo(2);
        assertThat(result.getData().getPageSize()).isEqualTo(5);
        assertThat(result.getData().getTotal()).isEqualTo(1);
        assertThat(result.getData().getRecords()).containsExactly(reportRepository.report);
        assertThat(reportRepository.lastStatuses).containsExactly("PENDING_CONFIRM", "PENDING_SPLIT_CONFIRM");
    }

    @Test
    void decryptedExportIsBlockedWith422() {
        ApiResult<Map<String, Object>> result = service.reportAction(
                "EXP-1",
                "download",
                "idem-l",
                new BiReportActionRequest("need raw data", "superadmin", true, true));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.RETIRED_FEATURE.httpStatus());
        assertThat(result.getMessage()).isEqualTo("DECRYPTED_PII_EXPORT_BLOCKED");
    }

    @Test
    void pendingSensitiveReportCanBeApprovedAndAudited() {
        ApiResult<Map<String, Object>> result = service.reportAction(
                "EXP-1",
                "approve",
                "idem-l",
                new BiReportActionRequest("approve masked export", "superadmin", true, false));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", "READY");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("L_BI_REPORT_APPROVE");
    }

    @Test
    void createReportPersistsAndAudits() {
        ApiResult<Map<String, Object>> result = service.createReport(
                "idem-create-l",
                new BiReportCreateRequest(
                        "create masked report",
                        "superadmin",
                        "账单 CSV",
                        "2026-06",
                        "金额/type/ref + 手机号(hash)",
                        "PII",
                        "默认脱敏",
                        "finance",
                        "T-1001"));

        assertThat(result.getCode()).isZero();
        assertThat(reportRepository.report.reportId()).startsWith("EXP-");
        assertThat(reportRepository.report.status()).isEqualTo("PENDING_CONFIRM");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("L_BI_REPORT_CREATE");
    }

    @Test
    void nonSensitiveReportIsReadyForDownloadImmediately() {
        ApiResult<Map<String, Object>> created = service.createReport(
                "idem-create-l-non-pii",
                new BiReportCreateRequest(
                        "create aggregate report",
                        "auditor",
                        "运营汇总 CSV",
                        "2026-06",
                        "订单数/金额/状态",
                        "NONE",
                        "无敏感字段",
                        "audit",
                        "T-1002"));

        assertThat(created.getCode()).isZero();
        assertThat(reportRepository.report.status()).isEqualTo("READY");

        ApiResult<Map<String, Object>> token = service.downloadToken(reportRepository.report.reportId());

        assertThat(token.getCode()).isZero();
        assertThat(token.getData()).containsEntry("reportId", reportRepository.report.reportId());
    }

    @Test
    void regulatorySchedulePayloadMutationIsDisabled() {
        ApiResult<Map<String, Object>> result = service.updateRegulatorySchedule(
                "idem-schedule-l",
                new BiDashboardValueRequest("每月 10 日", "adjust monthly schedule", "superadmin"));

        assertThat(result.getCode()).isEqualTo(501);
        assertThat(result.getMessage()).isEqualTo("BI_DASHBOARD_PAYLOAD_DISABLED");
    }

    @Test
    void downloadRequiresReadyStatus() {
        ApiResult<Map<String, Object>> result = service.reportAction(
                "EXP-1",
                "download",
                "idem-l",
                new BiReportActionRequest("download export", "superadmin", true, false));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void downloadFileUsesLiveLedgerSummaryAndAudits() {
        reportRepository.report = new BiReportView(
                "EXP-READY",
                "Bill CSV",
                "DetailExport",
                "OnDemand",
                "CSV",
                "full bills",
                "masked PII",
                100L,
                true,
                "masked",
                "READY",
                "confirm",
                null,
                null,
                null);
        ledgerRepository.counts.put(null, 12L);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("131", "N/A", List.of());
        authentication.setDetails(Map.of("username", "e2e_goal_audit_1"));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        ApiResult<BiReportDownloadFile> result = service.downloadFile("EXP-READY");

        assertThat(result.getCode()).isZero();
        assertThat(new String(result.getData().body(), StandardCharsets.UTF_8))
                .contains("ledger_bill_count")
                .contains("12");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("L_BI_REPORT_DOWNLOAD_FILE");
        assertThat(captor.getValue().getActorUsername()).isEqualTo("e2e_goal_audit_1");
    }

    private GrowthRhythmSnapshot h1Snapshot(int totalMonths, int currentMonth, String currentPhase) {
        return new GrowthRhythmSnapshot(
                totalMonths,
                currentMonth,
                currentPhase,
                58,
                BigDecimal.ONE,
                BigDecimal.ONE,
                new BigDecimal("50"),
                new BigDecimal("60"),
                new BigDecimal("10"),
                new BigDecimal("10"),
                new BigDecimal("100"),
                7,
                List.of("H1.rhythm.currentMonth"));
    }

    private static final class FakeTreasuryLedgerRepository implements TreasuryLedgerRepository {
        private final Map<String, Long> counts = new LinkedHashMap<>();
        private final List<TreasuryLedgerBillView> bills = new ArrayList<>();

        @Override
        public long countDeposits(LocalDateTime since, String status) {
            return counts.getOrDefault("DEPOSIT", 0L);
        }

        @Override
        public long countWithdrawals(LocalDateTime since, String status) {
            return counts.getOrDefault("WITHDRAWAL", 0L);
        }

        @Override
        public long countExchanges(LocalDateTime since, String status) {
            return counts.getOrDefault("EXCHANGE", 0L);
        }

        @Override
        public long countLedgers(LocalDateTime since, String direction) {
            return counts.getOrDefault(direction, 0L);
        }

        @Override
        public BigDecimal sumUsdtAvailable() {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal sumPendingWithdraw() {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal sumNexAvailable() {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal sumActiveStakingPrincipalUsdt() {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal sumActiveStakingInterestUsdt() {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal sumActiveNexLocked() {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal sumActiveNexReward() {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal sumActiveWithdrawalQueueUsdt() {
            return BigDecimal.ZERO;
        }

        @Override
        public long countActiveWithdrawalQueue() {
            return 0;
        }

        @Override
        public BigDecimal avgActiveWithdrawalQueueRiskScore() {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal sumPendingCommissionUsdt() {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal sumNetUsdtFlowBetween(LocalDateTime startAt, LocalDateTime endAt) {
            return BigDecimal.ZERO;
        }

        @Override
        public long countLedgerBills(String type, Long userId, String keyword) {
            return counts.getOrDefault(type, counts.getOrDefault(null, 0L));
        }

        @Override
        public List<TreasuryLedgerBillView> pageLedgerBills(String type, Long userId, String keyword, int pageSize, int offset) {
            return bills.stream().skip(offset).limit(pageSize).toList();
        }

        @Override
        public List<TreasuryLedgerBillView> userLedgerRows(Long userId, int limit) {
            return bills.stream().filter(row -> row.userId().equals(userId)).limit(limit).toList();
        }

        @Override
        public Optional<BigDecimal> currentUserBalance(Long userId, String asset) {
            return Optional.empty();
        }

        @Override
        public void createLedgerAdjustment(String adjustmentNo, Long userId, String asset, String direction,
                                           BigDecimal amount, String relatedBizNo, String reason, String operator) {
        }

        @Override
        public void postLedgerEntry(String bizNo, Long userId, String bizType, String asset, String direction,
                                    BigDecimal amount, String status, String remark) {
        }

        @Override
        public void seedD4FallbackData(Map<String, Long> userIds) {
        }
    }

    private static final class FakeBiReportRepository implements BiReportRepository {
        private BiReportView report = new BiReportView(
                "EXP-1",
                "Bill CSV",
                "DetailExport",
                "OnDemand",
                "CSV",
                "full bills",
                "masked PII",
                100L,
                true,
                "masked",
                "PENDING_CONFIRM",
                "confirm",
                null,
                null,
                null);
        private List<String> lastStatuses = List.of();

        @Override
        public Map<String, Object> overview() {
            return new LinkedHashMap<>(Map.of("totalReports", 1L, "sensitiveReports", 1L));
        }

        @Override
        public Map<String, Object> dashboard(String moduleCode) {
            if ("L6".equals(moduleCode)) {
                return new LinkedHashMap<>(Map.of(
                        "activityByWindow", Map.of("7d", List.of(Map.of("bucket", "account", "count", 1L, "source", "nx_user"))),
                        "sources", List.of("nx_user")));
            }
            return Map.of();
        }

        @Override
        public PageResult<BiReportView> reports(String type, List<String> statuses, int pageNum, int pageSize) {
            lastStatuses = statuses;
            return new PageResult<>(1, pageNum, pageSize, List.of(report));
        }

        @Override
        public Optional<BiReportView> findReport(String reportId) {
            return report.reportId().equals(reportId) ? Optional.of(report) : Optional.empty();
        }

        @Override
        public BiReportView createReport(BiReportCreateCommand command) {
            report = new BiReportView(
                    command.reportId(),
                    command.name(),
                    command.type(),
                    command.cycle(),
                    command.format(),
                    command.scope(),
                    command.fields(),
                    command.rowCount(),
                    command.containsPii(),
                    command.maskingPolicy(),
                    command.status(),
                    command.note(),
                    null,
                    null,
                    null);
            return report;
        }

        @Override
        public void updateAction(String reportId, String action, String nextStatus, String reason) {
            report = new BiReportView(
                    report.reportId(), report.name(), report.type(), report.cycle(), report.format(), report.scope(), report.fields(),
                    report.rowCount(), report.containsPii(), report.maskingPolicy(), nextStatus, report.note(), action, LocalDateTime.now(), reason);
        }
    }
}
