package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsBiServiceTest {
    private final FakeBiReportRepository reportRepository = new FakeBiReportRepository();
    private GrowthRhythmSnapshot h1Snapshot = h1Snapshot(12, 7, "P3");
    private final GrowthRhythmFacade growthRhythmFacade = () -> h1Snapshot;
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsBiService service = new OpsBiService(reportRepository, growthRhythmFacade, auditLogService);

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
        assertThat(result.getData().get("l1").toString()).contains("数据库 KPI");
        assertThat(result.getData().get("l3").toString()).contains("reserveCoverDays");
        assertThat(result.getData().get("l5").toString()).contains("数据库导出安全参数");
        assertThat(result.getData().get("l6").toString()).contains("数据库行为热力");
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
    void moduleOverviewsReadDashboardPayloadsFromRepository() {
        ApiResult<Map<String, Object>> l1 = service.kpiOverview();
        ApiResult<Map<String, Object>> l2 = service.funnelOverview();
        ApiResult<Map<String, Object>> l3 = service.financeOverview();
        ApiResult<Map<String, Object>> l4 = service.operationsOverview();
        ApiResult<Map<String, Object>> l5 = service.exportOverview();
        ApiResult<Map<String, Object>> l6 = service.behaviorHeatmapOverview("7d");

        assertThat(l1.getData().toString()).contains("数据库 KPI");
        assertThat(l2.getData().toString()).contains("数据库漏斗");
        assertThat(l3.getData().toString()).contains("reserveCoverDays");
        assertThat(l4.getData().toString()).contains("数据库设备");
        assertThat(l5.getData().toString()).contains("数据库导出安全参数");
        assertThat(l6.getData()).containsEntry("trackedCount", 1);
        assertThat(l6.getData().get("activity").toString()).contains("pages/index/index");
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
        assertThat(result.getData()).containsEntry("status", "GENERATING");
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
    void regulatoryScheduleIsSavedToDashboardPayload() {
        ApiResult<Map<String, Object>> result = service.updateRegulatorySchedule(
                "idem-schedule-l",
                new BiDashboardValueRequest("每月 10 日", "adjust monthly schedule", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(reportRepository.dashboard("L5")).containsEntry("scheduleDefault", "每月 10 日");
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
        private final Map<String, Map<String, Object>> dashboards = new LinkedHashMap<>();
        private List<String> lastStatuses = List.of();

        private FakeBiReportRepository() {
            dashboards.put("L1", new LinkedHashMap<>(Map.of("kpis", List.of(Map.of("name", "数据库 KPI")))));
            dashboards.put("L2", new LinkedHashMap<>(Map.of("funnel", List.of(Map.of("stage", "数据库漏斗")))));
            dashboards.put("L3", new LinkedHashMap<>(Map.of("reserveCoverDays", 88)));
            dashboards.put("L4", new LinkedHashMap<>(Map.of("deviceDistribution", List.of(Map.of("nm", "数据库设备")))));
            dashboards.put("L5", new LinkedHashMap<>(Map.of(
                    "exportParams", List.of(Map.of("k", "数据库导出安全参数", "fixed", false, "cur", "默认", "v", "默认")),
                    "scheduleOptions", List.of("每月 5 日", "每月 10 日"),
                    "scheduleDefault", "每月 5 日",
                    "regulatoryTemplates", List.of(Map.of("key", "kyc", "nm", "KYC")))));
            dashboards.put("L6", new LinkedHashMap<>(Map.of(
                    "trackedCount", 1,
                    "pageTree", List.of(Map.of("route", "pages/index/index", "titleZh", "数据库行为热力", "tracked", true)),
                    "excludedPages", List.of(),
                    "activityByWindow", Map.of("7d", List.of(Map.of("route", "pages/index/index", "pv", 120, "uv", 80, "clicks", 260, "dwellMs", 45000, "bounceRate", 0.22))),
                    "clickHeatByRoute", Map.of("pages/index/index", Map.of("route", "pages/index/index", "titleZh", "数据库行为热力", "zones", List.of(), "points", List.of())))));
        }

        @Override
        public Map<String, Object> overview() {
            return new LinkedHashMap<>(Map.of("totalReports", 1L, "sensitiveReports", 1L));
        }

        @Override
        public Map<String, Object> dashboard(String moduleCode) {
            return new LinkedHashMap<>(dashboards.getOrDefault(moduleCode, Map.of()));
        }

        @Override
        public void saveDashboard(String moduleCode, Map<String, Object> dashboard) {
            dashboards.put(moduleCode, new LinkedHashMap<>(dashboard));
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
