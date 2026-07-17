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
    private final OpsAuditController controller =
            new OpsAuditController(auditLogService, auditCenterService, operatorRoleResolver);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void overviewDelegatesToA2AuditCenterService() {
        AuditCenterOverview overview = new AuditCenterOverview(null, List.of(), List.of(), List.of(), List.of(),
                List.of(), null, List.of());
        when(auditCenterService.overview()).thenReturn(ApiResult.ok(overview));

        ApiResult<AuditCenterOverview> result = controller.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isSameAs(overview);
        verify(auditCenterService).overview();
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
        verify(auditLogService).record(captor.capture());
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
        AuditMechanismParamUpdateRequest request =
                new AuditMechanismParamUpdateRequest("12 字", "tighten reason", "superadmin");
        AuditCenterOverview.AuditMechanismParam param =
                new AuditCenterOverview.AuditMechanismParam("ttl", "理由最短长度", "reason", "12 字", false);
        when(auditCenterService.updateMechanismParam("idem-1", "ttl", request)).thenReturn(ApiResult.ok(param));

        assertThat(controller.updateMechanismParam("idem-1", "ttl", request).getData()).isSameAs(param);
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
