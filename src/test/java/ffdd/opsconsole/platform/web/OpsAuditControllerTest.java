package ffdd.opsconsole.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.audit.AuditStatsBucket;
import ffdd.opsconsole.shared.audit.AuditStatsQueryRequest;
import ffdd.opsconsole.shared.audit.AuditStatsSummaryResponse;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.application.OpsAuditCenterService;
import ffdd.opsconsole.platform.application.A2AccessPolicy;
import ffdd.opsconsole.platform.dto.AuditCenterOverview;
import ffdd.opsconsole.platform.dto.AuditExportRequest;
import ffdd.opsconsole.platform.dto.AuditMechanismParamUpdateRequest;
import ffdd.opsconsole.platform.dto.AuditOperationDecisionRequest;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsAuditControllerTest {
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsAuditCenterService auditCenterService = mock(OpsAuditCenterService.class);
    private final AdminOperatorRoleResolver operatorRoleResolver = mock(AdminOperatorRoleResolver.class);
    private final A2AccessPolicy accessPolicy = mock(A2AccessPolicy.class);
    private final ffdd.opsconsole.shared.idempotency.AdminIdempotencyService idempotencyService =
            mock(ffdd.opsconsole.shared.idempotency.AdminIdempotencyService.class);
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    private final OpsAuditController controller =
            new OpsAuditController(auditLogService, auditCenterService, operatorRoleResolver, accessPolicy,
                    idempotencyService, objectMapper);

    {
        when(accessPolicy.constrain(any(AuditLogQueryRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(idempotencyService.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> action = invocation.getArgument(4);
            return action.get();
        });
        when(auditLogService.count(any(AuditLogQueryRequest.class))).thenReturn(1L);
        when(auditLogService.listForExport(any(AuditLogQueryRequest.class), any(Integer.class)))
                .thenAnswer(invocation -> auditLogService.list(invocation.getArgument(0)));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void overviewDelegatesToA2AuditCenterService() {
        AuditCenterOverview overview = new AuditCenterOverview(null, List.of(), List.of(), List.of(), List.of(),
                List.of(), null, List.of());
        when(auditCenterService.overview(any(AuditLogQueryRequest.class))).thenReturn(ApiResult.ok(overview));

        ApiResult<AuditCenterOverview> result = controller.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isSameAs(overview);
        verify(auditCenterService).overview(any(AuditLogQueryRequest.class));
    }

    @Test
    void logsDelegatesToA2AuditService() {
        AuditLogRecord record = new AuditLogRecord();
        when(auditLogService.list(any(AuditLogQueryRequest.class))).thenReturn(List.of(record));

        ApiResult<List<AuditLogRecord>> result = controller.logs(new AuditLogQueryRequest());

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsExactly(record);
        verify(auditLogService).list(any(AuditLogQueryRequest.class));
    }

    @Test
    void traceLookupCapsLimitAtTwoHundred() {
        when(auditLogService.list(any(AuditLogQueryRequest.class))).thenReturn(List.of());

        controller.byTrace("trace-001");

        ArgumentCaptor<AuditLogQueryRequest> captor = ArgumentCaptor.forClass(AuditLogQueryRequest.class);
        verify(auditLogService).list(captor.capture());
        assertThat(captor.getValue().getTraceId()).isEqualTo("trace-001");
        assertThat(captor.getValue().getLimit()).isEqualTo(200);
    }

    @Test
    void exportRequiresReason() {
        AuditExportRequest request = new AuditExportRequest(null, Map.of("action", "LOGIN"));

        ResponseEntity<?> result = controller.export("idem-1", request);

        assertThat(result.getStatusCode().value()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
        ApiResult<?> body = (ApiResult<?>) result.getBody();
        assertThat(body.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
        assertThat(body.getMessage()).isEqualTo(OpsErrorCode.REASON_REQUIRED.name());
    }

    @Test
    void exportRequiresIdempotencyKey() {
        AuditExportRequest request = new AuditExportRequest("incident review", Map.of("action", "LOGIN"));

        ResponseEntity<?> result = controller.export(" ", request);

        assertThat(result.getStatusCode().value()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
        ApiResult<?> body = (ApiResult<?>) result.getBody();
        assertThat(body.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
        assertThat(body.getMessage()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
    }

    @Test
    void exportWritesA2AuditRecordWithReasonAndIdempotencyKey() {
        AuditExportRequest request = new AuditExportRequest("incident review", Map.of("action", "LOGIN"));
        AuditLogRecord record = new AuditLogRecord();
        record.setAction("LOGIN");
        record.setResourceType("ADMIN_SESSION");
        record.setResourceId("login-1");
        record.setActorUsername("superadmin");
        record.setResult("SUCCESS");
        record.setRiskLevel("LOW");
        when(auditLogService.list(any(AuditLogQueryRequest.class))).thenReturn(List.of(record));

        ResponseEntity<?> result = controller.export("idem-1", request);

        assertThat(result.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(result.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains(".xls");
        String workbook = new String((byte[]) result.getBody(), StandardCharsets.UTF_8);
        assertThat(workbook).contains("A2 审计日志导出", "incident review", "LOGIN", "superadmin");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("A2_AUDIT_EXPORTED");
        assertThat(captor.getValue().getResourceType()).isEqualTo("A2_AUDIT_EXPORT");
        assertThat(captor.getValue().getRiskLevel()).isEqualTo("MEDIUM");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("reason", "incident review")
                .containsEntry("idempotencyKey", "idem-1");
    }

    @Test
    void operationDecisionEndpointsDelegateToAuditCenterService() {
        AuditOperationDecisionRequest request = new AuditOperationDecisionRequest("verified", "superadmin");
        AuditCenterOverview.AuditOperationTicket ticket =
                new AuditCenterOverview.AuditOperationTicket("WO-8852", "提现放行", "usr", "review", "approved",
                        "superadmin", "super", "fund", true, false, "2m", false, "超管", "verified", "approved");
        when(auditCenterService.approve("idem-1", "WO-8852", request)).thenReturn(ApiResult.ok(ticket));
        when(auditCenterService.reject("idem-2", "WO-8851", request)).thenReturn(ApiResult.ok(ticket));

        assertThat(controller.approveOperation("idem-1", "WO-8852", request).getData()).isSameAs(ticket);
        assertThat(controller.rejectOperation("idem-2", "WO-8851", request).getData()).isSameAs(ticket);
    }

    @Test
    void webEndpointsReplaceSpoofedOperatorsWithAuthenticatedUsername() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("41", null, List.of());
        authentication.setDetails(Map.of("subjectType", "ADMIN", "username", "alice.admin"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(operatorRoleResolver.resolve()).thenReturn("超管");
        AuditOperationDecisionRequest spoofedDecision = new AuditOperationDecisionRequest("verified", "mallory");
        AuditOperationProposalRequest spoofedProposal = new AuditOperationProposalRequest(
                "A6 grant", "role:9", "read", "write", "mallory", "ADMIN", "HIGH",
                true, false, "TWO_PERSON", "grant review", "A", null, null, null);

        controller.approveOperation("idem-approve", "WO-1", spoofedDecision);
        controller.rejectOperation("idem-reject", "WO-2", spoofedDecision);
        controller.createOperation("idem-create", spoofedProposal);

        verify(auditCenterService).approve("idem-approve", "WO-1",
                new AuditOperationDecisionRequest("verified", "alice.admin"));
        verify(auditCenterService).reject("idem-reject", "WO-2",
                new AuditOperationDecisionRequest("verified", "alice.admin"));
        ArgumentCaptor<AuditOperationProposalRequest> proposal =
                ArgumentCaptor.forClass(AuditOperationProposalRequest.class);
        verify(auditCenterService).createProposal(org.mockito.ArgumentMatchers.eq("idem-create"), proposal.capture());
        assertThat(proposal.getValue().operator()).isEqualTo("alice.admin");
        assertThat(proposal.getValue().operatorRole()).isEqualTo("超管");
    }

    @Test
    void mechanismParamEndpointDelegatesToAuditCenterService() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("1", null, List.of());
        authentication.setDetails(Map.of("subjectType", "ADMIN", "username", "superadmin"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        AuditMechanismParamUpdateRequest request =
                new AuditMechanismParamUpdateRequest("12 字", "tighten reason", "superadmin");
        AuditCenterOverview.AuditMechanismParam param =
                new AuditCenterOverview.AuditMechanismParam("ttl", "理由最短长度", "reason", "12 字", false);
        when(auditCenterService.updateMechanismParam("idem-1", "ttl", request)).thenReturn(ApiResult.ok(param));

        assertThat(controller.updateMechanismParam("idem-1", "ttl", request).getData()).isSameAs(param);
    }

    @Test
    void mechanismParamEndpointReplacesSpoofedOperatorWithAuthenticatedUsername() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("41", null, List.of());
        authentication.setDetails(Map.of("subjectType", "ADMIN", "username", "alice.admin"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        AuditMechanismParamUpdateRequest spoofed =
                new AuditMechanismParamUpdateRequest("12", "tighten reason", "mallory");

        controller.updateMechanismParam("idem-1", "ttl", spoofed);

        verify(auditCenterService).updateMechanismParam("idem-1", "ttl",
                new AuditMechanismParamUpdateRequest("12", "tighten reason", "alice.admin"));
    }

    @Test
    void workbookNeutralizesSpreadsheetFormulaCells() {
        AuditLogRecord record = new AuditLogRecord();
        record.setAction("  =HYPERLINK(\"https://evil\")");
        record.setActorUsername("\t+cmd|' /C calc'!A0");
        record.setResourceType("\r-2+3");
        record.setResourceId("\n@SUM(1,1)");
        when(auditLogService.list(any(AuditLogQueryRequest.class))).thenReturn(List.of(record));

        ResponseEntity<?> result = controller.export("idem-formula",
                new AuditExportRequest("security review", Map.of()));

        String workbook = new String((byte[]) result.getBody(), StandardCharsets.UTF_8);
        assertThat(workbook).contains("'  =HYPERLINK", "'\t+cmd", "'\r-2+3", "'\n@SUM");
    }

    @Test
    void exportUsesFixedA2ScopeAndSerializableReplayPayload() throws Exception {
        AuditLogRecord record = new AuditLogRecord();
        record.setAction("C2_ACCOUNT_REVIEWED");
        when(auditLogService.list(any(AuditLogQueryRequest.class))).thenReturn(List.of(record));

        controller.export("idem-fixed-scope",
                new AuditExportRequest("security evidence export", Map.of("domain", "C")));

        verify(idempotencyService).execute(org.mockito.ArgumentMatchers.eq("A2_COMMAND"),
                org.mockito.ArgumentMatchers.eq("idem-fixed-scope"), any(),
                org.mockito.ArgumentMatchers.eq(OpsAuditController.AuditExportResult.class), any());
        OpsAuditController.AuditExportResult expected = new OpsAuditController.AuditExportResult(
                "job-1", java.time.LocalDateTime.of(2026, 7, 17, 12, 0), new byte[]{1, 2, 3});
        byte[] json = objectMapper.writeValueAsBytes(expected);
        assertThat(objectMapper.readValue(json, OpsAuditController.AuditExportResult.class).body())
                .containsExactly(1, 2, 3);
    }

    @Test
    void exportReplayReturnsStoredWorkbookWithoutSecondAudit() {
        AuditLogRecord record = new AuditLogRecord();
        record.setAction("D2_EXPORTABLE");
        when(auditLogService.list(any(AuditLogQueryRequest.class))).thenReturn(List.of(record));
        java.util.concurrent.atomic.AtomicReference<Object> stored = new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (stored.get() == null) stored.set(((java.util.function.Supplier<?>) invocation.getArgument(4)).get());
            return stored.get();
        }).when(idempotencyService).execute(org.mockito.ArgumentMatchers.eq("A2_COMMAND"),
                org.mockito.ArgumentMatchers.eq("idem-export-replay"), any(), any(), any());
        AuditExportRequest request = new AuditExportRequest("replay evidence export", Map.of("domain", "D"));

        ResponseEntity<?> first = controller.export("idem-export-replay", request);
        ResponseEntity<?> second = controller.export("idem-export-replay", request);

        assertThat((byte[]) second.getBody()).containsExactly((byte[]) first.getBody());
        verify(auditLogService, org.mockito.Mockito.times(1)).recordRequired(any(AuditLogWriteRequest.class));
    }

    @Test
    void exportRejectsEmptyResultAndOverlongReasonWithoutSuccessAudit() {
        when(auditLogService.list(any(AuditLogQueryRequest.class))).thenReturn(List.of());
        when(auditLogService.count(any(AuditLogQueryRequest.class))).thenReturn(0L);
        assertThat(controller.export("idem-empty",
                new AuditExportRequest("empty result evidence", Map.of())).getStatusCode().value()).isEqualTo(422);
        assertThat(controller.export("idem-long",
                new AuditExportRequest("x".repeat(201), Map.of())).getStatusCode().value())
                .isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        verify(auditLogService, org.mockito.Mockito.never()).recordRequired(any());
    }

    @Test
    void exportNeverSilentlyTruncatesMoreThanFiveThousandRows() {
        when(auditLogService.count(any(AuditLogQueryRequest.class))).thenReturn(5001L);
        ResponseEntity<?> result = controller.export("idem-too-large",
                new AuditExportRequest("large export evidence", Map.of()));
        assertThat(result.getStatusCode().value()).isEqualTo(422);
        assertThat(((ApiResult<?>) result.getBody()).getMessage()).isEqualTo("A2_EXPORT_TOO_LARGE_REFINE_FILTER");
        verify(auditLogService, org.mockito.Mockito.never()).listForExport(any(), any(Integer.class));
        verify(auditLogService, org.mockito.Mockito.never()).recordRequired(any());
    }

    @Test
    void exportIncludesAllTwoHundredAndOneRowsInsteadOfUsingUiListCap() {
        AuditLogRecord record = new AuditLogRecord(); record.setAction("D2_ROW");
        when(auditLogService.count(any(AuditLogQueryRequest.class))).thenReturn(201L);
        when(auditLogService.list(any(AuditLogQueryRequest.class)))
                .thenReturn(java.util.Collections.nCopies(201, record));
        ResponseEntity<?> result = controller.export("idem-201",
                new AuditExportRequest("complete export evidence", Map.of("domain", "D")));
        assertThat(result.getStatusCode().is2xxSuccessful()).isTrue();
        verify(auditLogService).listForExport(any(AuditLogQueryRequest.class),
                org.mockito.ArgumentMatchers.eq(5000));
    }

    @Test
    void statsEndpointsDelegateToAuditService() {
        AuditStatsSummaryResponse summary = new AuditStatsSummaryResponse();
        when(auditLogService.summary(any(AuditStatsQueryRequest.class))).thenReturn(summary);
        when(auditLogService.topActions(any(AuditStatsQueryRequest.class)))
                .thenReturn(List.of(new AuditStatsBucket("LOGIN", 3L)));
        when(auditLogService.topServices(any(AuditStatsQueryRequest.class)))
                .thenReturn(List.of(new AuditStatsBucket("nexion-backend", 2L)));
        when(auditLogService.topUsers(any(AuditStatsQueryRequest.class)))
                .thenReturn(List.of(new AuditStatsBucket("1001", 1L)));

        assertThat(controller.summary(new AuditStatsQueryRequest()).getData()).isSameAs(summary);
        assertThat(controller.actions(new AuditStatsQueryRequest()).getData()).hasSize(1);
        assertThat(controller.services(new AuditStatsQueryRequest()).getData()).hasSize(1);
        assertThat(controller.users(new AuditStatsQueryRequest()).getData()).hasSize(1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
    }
}
