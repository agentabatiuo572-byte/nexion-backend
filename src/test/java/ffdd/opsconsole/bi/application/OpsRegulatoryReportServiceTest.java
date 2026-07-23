package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.bi.domain.BiReportCreateCommand;
import ffdd.opsconsole.bi.domain.BiReportRepository;
import ffdd.opsconsole.bi.domain.BiReportView;
import ffdd.opsconsole.bi.dto.RegulatoryReportRequest;
import ffdd.opsconsole.content.facade.RegulatoryDisclosureFacade;
import ffdd.opsconsole.content.facade.RegulatoryDisclosureSnapshot;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import ffdd.opsconsole.treasury.facade.TreasuryFinanceAnalyticsFacade;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsRegulatoryReportServiceTest {
    private final BiReportRepository reportRepository = mock(BiReportRepository.class);
    private final TreasuryLedgerRepository ledgerRepository = mock(TreasuryLedgerRepository.class);
    private final TreasuryFinanceAnalyticsFacade financeFacade = mock(TreasuryFinanceAnalyticsFacade.class);
    private final RegulatoryDisclosureFacade disclosureFacade = mock(RegulatoryDisclosureFacade.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final OpsRegulatoryReportService service = new OpsRegulatoryReportService(
            reportRepository, ledgerRepository, financeFacade, disclosureFacade, auditLogService, idempotencyService);

    @BeforeEach
    void replayIdempotentAction() {
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotencyService).execute(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void rejectsUnboundedReasonBeforeReadingDisclosureOrWritingTask() {
        ApiResult<Map<String, Object>> result = service.create("idem-1", request("short"));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("REGULATORY_REASON_LENGTH_INVALID");
        verify(disclosureFacade, never()).resolveCurrent(anyString(), anyString());
        verify(reportRepository, never()).createReport(any());
    }

    @Test
    void rejectsAStaleOrNonCurrentI5DisclosureVersion() {
        when(disclosureFacade.resolveCurrent("VN", "v0")).thenReturn(Optional.empty());
        RegulatoryReportRequest stale = new RegulatoryReportRequest(
                "AML_REPORT", "2026-07", "VN", "v0", "合规团队", "99105-L5-REG", "验收监管报告当前披露版本", "ignored");

        ApiResult<Map<String, Object>> result = service.create("idem-2", stale);

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("I5_CURRENT_DISCLOSURE_NOT_FOUND");
        verify(reportRepository, never()).createReport(any());
    }

    @Test
    void persistsAMinimizedSnapshotWithCrossDomainEvidenceAndRequiredAudit() {
        RegulatoryDisclosureSnapshot disclosure = new RegulatoryDisclosureSnapshot(
                "VN", "越南", List.of("VN"), "v1", "published", "hash-1", 7,
                "2026-07-01", 100L, 75.5D, 4L);
        when(disclosureFacade.resolveCurrent("VN", "v1")).thenReturn(Optional.of(disclosure));
        when(reportRepository.dashboard("L2")).thenReturn(Map.of("stages", List.of(
                Map.of("key", "kycSubmitted", "count", 12L),
                Map.of("key", "kycApproved", "count", 9L))));
        when(financeFacade.currentFinanceSnapshot()).thenReturn(Map.of("snapshot", Map.of(
                "reserveUsd", 1000, "liabilitiesUsd", 800, "coverageRatio", 125,
                "netFlow24hUsd", 25, "queueBacklogCount", 2, "queueBacklogUsd", 40)));
        when(reportRepository.countRegisteredServerEvent("admin.emergency_playbook_executed")).thenReturn(3L);
        when(ledgerRepository.countLedgerBills(null, null, null)).thenReturn(18L);
        when(reportRepository.createReport(any())).thenAnswer(invocation -> view(invocation.getArgument(0)));

        ApiResult<Map<String, Object>> result = service.create("idem-3", request("99105监管报告生成验收用途"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<String> csv = ArgumentCaptor.forClass(String.class);
        verify(reportRepository).saveSnapshotCsv(anyString(), csv.capture());
        assertThat(csv.getValue())
                .contains("disclosure_version", "aggregate_only_no_user_rows", "C4", "L3", "D4", "A2", "J4")
                .doesNotContain("user_id", "phone", "passport");
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("admin.report_exported");
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) audit.getValue().getDetail();
        assertThat(detail)
                .containsEntry("containsPii", false)
                .containsEntry("jurisdictionCode", "VN")
                .containsEntry("disclosureVersion", "v1")
                .containsEntry("chapterCount", 7);
    }

    @Test
    void missingC4AggregateIsExplicitlyUnavailableInsteadOfInventingZero() {
        RegulatoryDisclosureSnapshot disclosure = new RegulatoryDisclosureSnapshot(
                "VN", "越南", List.of("VN"), "v1", "published", "hash-1", 7,
                "2026-07-01", 100L, 75.5D, 4L);
        when(disclosureFacade.resolveCurrent("VN", "v1")).thenReturn(Optional.of(disclosure));
        when(reportRepository.dashboard("L2")).thenReturn(Map.of());
        when(reportRepository.createReport(any())).thenAnswer(invocation -> view(invocation.getArgument(0)));
        RegulatoryReportRequest kyc = new RegulatoryReportRequest(
                "KYC_COMPLIANCE", "2026-07", "VN", "v1", "合规团队", "99105-L5-REG",
                "99105监管报告缺失源验收", "ignored");

        ApiResult<Map<String, Object>> result = service.create("idem-missing-c4", kyc);

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<String> csv = ArgumentCaptor.forClass(String.class);
        verify(reportRepository).saveSnapshotCsv(anyString(), csv.capture());
        assertThat(csv.getValue())
                .contains("kyc_submitted_users","kyc_verified_users", "UNAVAILABLE", "no value inferred")
                .doesNotContain("\"kyc_submitted_users\",\"0\"")
                .doesNotContain("\"kyc_verified_users\",\"0\"");
    }

    private RegulatoryReportRequest request(String reason) {
        return new RegulatoryReportRequest(
                "AML_REPORT", "2026-07", "VN", "v1", "合规团队", "99105-L5-REG", reason, "ignored");
    }

    private BiReportView view(BiReportCreateCommand command) {
        return new BiReportView(
                command.reportId(), command.name(), command.type(), command.cycle(), command.format(),
                command.scope(), command.fields(), command.rowCount(), command.containsPii(), command.maskingPolicy(),
                command.status(), command.note(), null, null, null, false);
    }
}
