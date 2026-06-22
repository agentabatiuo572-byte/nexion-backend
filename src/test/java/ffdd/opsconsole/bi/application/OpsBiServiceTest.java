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
import ffdd.opsconsole.bi.dto.BiReportActionRequest;
import ffdd.opsconsole.bi.dto.BiReportQueryRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsBiServiceTest {
    private final FakeBiReportRepository reportRepository = new FakeBiReportRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsBiService service = new OpsBiService(reportRepository, auditLogService);

    @Test
    void overviewDeclaresSensitiveExportRules() {
        ApiResult<Map<String, Object>> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("securityParams").toString())
                .contains("confirm-with-reason")
                .contains("24 hours")
                .contains("decrypted PII export is blocked");
        assertThat(result.getData())
                .containsKeys("currentPhase", "phases", "l1", "l2", "l3", "l4");
        assertThat(result.getData().get("l1").toString()).contains("Day 7 留存");
        assertThat(result.getData().get("l3").toString()).contains("reserveCoverDays");
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
    void downloadRequiresReadyStatus() {
        ApiResult<Map<String, Object>> result = service.reportAction(
                "EXP-1",
                "download",
                "idem-l",
                new BiReportActionRequest("download export", "superadmin", true, false));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
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

        @Override
        public Map<String, Object> overview() {
            return new LinkedHashMap<>(Map.of("totalReports", 1L, "sensitiveReports", 1L));
        }

        @Override
        public PageResult<BiReportView> reports(String type, List<String> statuses, int pageNum, int pageSize) {
            lastStatuses = statuses;
            return new PageResult<>(1, pageNum, pageSize, List.of(report));
        }

        private List<String> lastStatuses = List.of();

        @Override
        public Optional<BiReportView> findReport(String reportId) {
            return report.reportId().equals(reportId) ? Optional.of(report) : Optional.empty();
        }

        @Override
        public void updateAction(String reportId, String action, String nextStatus, String reason) {
            report = new BiReportView(
                    report.reportId(), report.name(), report.type(), report.cycle(), report.format(), report.scope(), report.fields(),
                    report.rowCount(), report.containsPii(), report.maskingPolicy(), nextStatus, report.note(), action, LocalDateTime.now(), reason);
        }
    }
}
