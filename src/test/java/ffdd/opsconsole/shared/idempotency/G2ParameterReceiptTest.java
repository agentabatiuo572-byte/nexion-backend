package ffdd.opsconsole.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.application.A2AccessPolicy;
import ffdd.opsconsole.platform.mapper.AuditOperationTicketMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.mapper.AdminCommandInvestigationMapper;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import ffdd.opsconsole.shared.security.SuperAdminAuthorization;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class G2ParameterReceiptTest {
    @AfterEach
    void resetContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void capturesOwnerOnlyForTheCorrespondingG2ParameterPatch() {
        var principal = UsernamePasswordAuthenticationToken.authenticated("42", "", java.util.List.of());
        principal.setDetails(Map.of("subjectType", "ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(principal);
        var path = "/api/admin/market/exchange/params/queueEtaCopy";
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("PATCH", path)));
        var origin = AdminCommandOrigin.current("ADMIN:G2:PARAM:queueEtaCopy");
        assertThat(origin).isNotNull();
        assertThat(origin.actorId()).isEqualTo(42);
        assertThat(origin.path()).isEqualTo(path);
        assertThat(origin.target()).isEqualTo("queueEtaCopy");
        assertThat(origin.readAuthority()).isEqualTo("finprod_g2_read");
        assertThat(AdminCommandOrigin.readAuthority("ADMIN:G2:PARAM:QUEUEETACOPYZH")).isEqualTo("finprod_g2_read");
        assertThat(AdminCommandOrigin.current("ADMIN:G2:PARAM:fee")).isNull();
        assertThat(AdminCommandOrigin.supported("ADMIN:G2:PARAM:UNKNOWN")).isFalse();
    }
    @Test
    void statusUsesOnlyTheBoundOriginalReceipt() {
        var principal = UsernamePasswordAuthenticationToken.authenticated("42", "", java.util.List.of());
        principal.setDetails(Map.of("subjectType", "ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(principal);
        var records = mock(AdminIdempotencyRecordMapper.class);
        var permissions = mock(AdminPermissionCache.class);
        var administrators = mock(AdminOperatorRoleResolver.class);
        when(administrators.isActiveAdministrator(42L)).thenReturn(true);
        var service = new AdminCommandInvestigationService(records, mock(AdminCommandInvestigationMapper.class),
                permissions, administrators, mock(SuperAdminAuthorization.class),
                mock(AuditLogService.class), mock(A2AccessPolicy.class), mock(AuditOperationTicketMapper.class),
                new ObjectMapper(), Clock.systemUTC());
        String scope = "ADMIN:G2:PARAM:QUEUEETACOPY";
        var row = new AdminIdempotencyRecordEntity();
        row.setScope(scope);
        row.setIsDeleted(0);
        when(records.selectCurrent(scope, "key")).thenReturn(row);
        when(permissions.getPermissionCodes(42L)).thenReturn(Set.of("finprod_g2_read"));
        assertThat(service.status(scope, "missing").state()).isEqualTo("NOT_FOUND");
        row.setStatus("SUCCEEDED");
        row.setResponseJson("{\"code\":0}");
        assertThat(service.status(scope, "key").state()).isEqualTo("UNKNOWN");
        row.setOriginAdminId(43L);
        assertThat(service.status(scope, "key").state()).isEqualTo("UNKNOWN");
        row.setOriginAdminId(42L);
        assertThat(service.status("ADMIN:G2:PARAM:queueEtaCopy", "key").state()).isEqualTo("SUCCEEDED");
        row.setStatus("PROCESSING");
        assertThat(service.status(scope, "key").state()).isEqualTo("PROCESSING");
        row.setStatus("FAILED");
        row.setResponseJson(null);
        assertThat(service.status(scope, "key").state()).isEqualTo("UNKNOWN");
        row.setResponseJson("{\"code\":422,\"message\":\"VALUE_REQUIRED\"}");
        assertThat(service.status(scope, "key").state()).isEqualTo("REJECTED");
        when(permissions.getPermissionCodes(42L)).thenReturn(Set.of());
        assertThatThrownBy(() -> service.status(scope, "key")).isInstanceOfSatisfying(BizException.class,
                error -> assertThat(error.getCode()).isEqualTo(403));
    }
    @Test
    void rejectedReceiptReplaysWithoutExecutingAndDoesNotCrossOwners() {
        var records = mock(AdminIdempotencyRecordMapper.class);
        var executor = new AdminIdempotencyTransactionExecutor(records, new ObjectMapper(),
                mock(AdminIdempotencyExpiryTransitionExecutor.class), mock(AdminIdempotencyRollbackEvidence.class));
        String scope = "ADMIN:G2:PARAM:FEE";
        var original = new AdminIdempotencyRecordEntity();
        original.setScope(scope);
        original.setStatus("FAILED");
        original.setRequestHash("hash");
        original.setOriginAdminId(42L);
        original.setResponseJson("{\"code\":422,\"message\":\"VALUE_REQUIRED\"}");
        when(records.selectActive(scope, "key")).thenReturn(original);
        var owner = new AdminCommandOrigin(42L, "PATCH", "/api/admin/market/exchange/params/fee", "fee", "finprod_g2_read", java.util.List.of());
        var other = new AdminCommandOrigin(43L, owner.method(), owner.path(), owner.target(), owner.readAuthority(), owner.targets());
        var claim = executor.claimBound(scope, "key", "hash", LocalDateTime.now(), ApiResult.class, false, owner);
        assertThat(claim.executeAction()).isFalse();
        assertThat(claim.replayResponse().getCode()).isEqualTo(422);
        assertThatThrownBy(() -> executor.claimBound(scope, "key", "hash", LocalDateTime.now(), ApiResult.class, false, other))
                .hasMessage("IDEMPOTENCY_RESULT_UNKNOWN");
        assertThatThrownBy(() -> executor.claimBound(scope, "key", "different", LocalDateTime.now(), ApiResult.class, false, owner))
                .hasMessage("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
    }
}
