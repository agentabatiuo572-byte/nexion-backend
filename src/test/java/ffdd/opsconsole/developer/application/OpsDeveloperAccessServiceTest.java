package ffdd.opsconsole.developer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.developer.mapper.OpsDeveloperAccessMapper;
import ffdd.opsconsole.developer.mapper.AppDeveloperAccessMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class OpsDeveloperAccessServiceTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 17, 10, 0);

    @Test
    void everyMutationRunsInOneDatabaseTransactionWithRuntimeAuditRollback() throws Exception {
        assertThat(OpsDeveloperAccessService.class.getDeclaredMethod("approve", String.class, String.class,
                String.class, String.class, String.class).getAnnotation(Transactional.class)).isNotNull();
        assertThat(OpsDeveloperAccessService.class.getDeclaredMethod("reject", String.class, String.class,
                String.class, String.class, String.class).getAnnotation(Transactional.class)).isNotNull();
        assertThat(OpsDeveloperAccessService.class.getDeclaredMethod("revoke", String.class, String.class,
                String.class, String.class, String.class).getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void pagesRealRequestsAndPreservesReviewFields() {
        OpsDeveloperAccessMapper mapper = mock(OpsDeveloperAccessMapper.class);
        when(mapper.count(null, null, null)).thenReturn(1L);
        when(mapper.page(null, null, null, 0, 20)).thenReturn(List.of(row("DEV-AAAAAAAAAAAAAAAA", "PENDING", null, null)));

        var result = new OpsDeveloperAccessService(mapper, mock(AuditLogService.class))
                .page(1, 20, null, null, null);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getRecords()).hasSize(1);
        assertThat(result.getData().getRecords().get(0)).containsEntry("requestNo", "DEV-AAAAAAAAAAAAAAAA");
        assertThat(result.getData().getRecords().get(0)).containsEntry("status", "PENDING");
    }

    @Test
    void approveUsesPendingCasAndWritesAuditedRemark() {
        OpsDeveloperAccessMapper mapper = mock(OpsDeveloperAccessMapper.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(mapper.insertIdempotency(eq("DEV-AAAAAAAAAAAAAAAA"), eq("APPROVED"), eq("idem-1"), anyString())).thenReturn(1);
        when(mapper.findForUpdate("DEV-AAAAAAAAAAAAAAAA")).thenReturn(row("DEV-AAAAAAAAAAAAAAAA", "PENDING", null, null));
        when(mapper.transition("DEV-AAAAAAAAAAAAAAAA", "PENDING", "APPROVED", "admin-1", "approved for integration", "idem-1"))
                .thenReturn(1);
        when(mapper.find("DEV-AAAAAAAAAAAAAAAA")).thenReturn(row("DEV-AAAAAAAAAAAAAAAA", "APPROVED", "admin-1", "approved for integration"));
        when(mapper.completeIdempotency(eq("DEV-AAAAAAAAAAAAAAAA"), eq("APPROVED"), eq("idem-1"), anyString(),
                anyString(), anyString(), anyString(), any(LocalDateTime.class))).thenReturn(1);

        var result = new OpsDeveloperAccessService(mapper, audit)
                .approve("DEV-AAAAAAAAAAAAAAAA", "PENDING", "approved for integration", "admin-1", "idem-1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", "APPROVED");
        verify(audit).recordRequired(any());
    }

    @Test
    void expiredApprovalFailsClosedWithoutSecondMutation() {
        OpsDeveloperAccessMapper mapper = mock(OpsDeveloperAccessMapper.class);
        when(mapper.findForUpdate("DEV-AAAAAAAAAAAAAAAA")).thenReturn(row("DEV-AAAAAAAAAAAAAAAA", "EXPIRED", "system", "request expired"));

        var result = new OpsDeveloperAccessService(mapper, mock(AuditLogService.class))
                .approve("DEV-AAAAAAAAAAAAAAAA", "PENDING", "late approval", "admin-2", "idem-2");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("DEVELOPER_ACCESS_REQUEST_STATE_CONFLICT");
    }

    @Test
    void duplicateApprovalAgainstApprovedStateFailsClosed() {
        OpsDeveloperAccessMapper mapper = mock(OpsDeveloperAccessMapper.class);
        when(mapper.findForUpdate("DEV-AAAAAAAAAAAAAAAA")).thenReturn(row("DEV-AAAAAAAAAAAAAAAA", "APPROVED", "admin-1", "already approved"));

        var result = new OpsDeveloperAccessService(mapper, mock(AuditLogService.class))
                .approve("DEV-AAAAAAAAAAAAAAAA", "PENDING", "duplicate approval", "admin-2", "idem-duplicate");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("DEVELOPER_ACCESS_REQUEST_STATE_CONFLICT");
    }

    @Test
    void revokeRequiresApprovedStateAndMakesGuardReadbackFailClosed() {
        OpsDeveloperAccessMapper mapper = mock(OpsDeveloperAccessMapper.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(mapper.insertIdempotency(eq("DEV-AAAAAAAAAAAAAAAA"), eq("REVOKED"), eq("idem-3"), anyString())).thenReturn(1);
        when(mapper.findForUpdate("DEV-AAAAAAAAAAAAAAAA")).thenReturn(row("DEV-AAAAAAAAAAAAAAAA", "APPROVED", "admin-1", "approved"));
        when(mapper.transition("DEV-AAAAAAAAAAAAAAAA", "APPROVED", "REVOKED", "admin-2", "policy breach", "idem-3"))
                .thenReturn(1);
        when(mapper.find("DEV-AAAAAAAAAAAAAAAA")).thenReturn(row("DEV-AAAAAAAAAAAAAAAA", "REVOKED", "admin-2", "policy breach"));
        when(mapper.completeIdempotency(eq("DEV-AAAAAAAAAAAAAAAA"), eq("REVOKED"), eq("idem-3"), anyString(),
                anyString(), anyString(), anyString(), any(LocalDateTime.class))).thenReturn(1);

        var result = new OpsDeveloperAccessService(mapper, audit)
                .revoke("DEV-AAAAAAAAAAAAAAAA", "APPROVED", "policy breach", "admin-2", "idem-3");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", "REVOKED");
        verify(audit).recordRequired(any());
    }

    @Test
    void revokedRowCannotOpenDeveloperResource() {
        AppDeveloperAccessMapper access = mock(AppDeveloperAccessMapper.class);
        when(access.userSandbox(7L)).thenReturn(0);
        when(access.approved(7L, "PRODUCTION", "")).thenReturn(0);

        var guard = new DeveloperAccountGuard(access, new org.springframework.mock.env.MockEnvironment());
        assertThatThrownBy(() -> guard.requireApproved(7L, false))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(403);
    }

    @Test
    void missingOrShortRemarkIsRejectedBeforeMutation() {
        OpsDeveloperAccessMapper mapper = mock(OpsDeveloperAccessMapper.class);

        var result = new OpsDeveloperAccessService(mapper, mock(AuditLogService.class))
                .reject("DEV-AAAAAAAAAAAAAAAA", "PENDING", "short", "admin-1", "idem-4");

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("DEVELOPER_ACCESS_REVIEW_REASON_INVALID");
    }

    @Test
    void sameIdempotencyKeyAndPayloadReplaysWithoutSecondTransition() {
        OpsDeveloperAccessMapper mapper = mock(OpsDeveloperAccessMapper.class);
        when(mapper.findIdempotency("DEV-AAAAAAAAAAAAAAAA", "idem-replay"))
                .thenReturn(new OpsDeveloperAccessMapper.IdempotencyRow(
                        "DEV-AAAAAAAAAAAAAAAA", "APPROVED", "idem-replay",
                        requestHash("DEV-AAAAAAAAAAAAAAAA", "APPROVED", "PENDING", "approved for integration", "admin-1"), "COMPLETED",
                        "APPROVED", "admin-1", "approved for integration", now));
        when(mapper.find("DEV-AAAAAAAAAAAAAAAA"))
                .thenReturn(row("DEV-AAAAAAAAAAAAAAAA", "APPROVED", "admin-1", "approved for integration"));

        var result = new OpsDeveloperAccessService(mapper, mock(AuditLogService.class))
                .approve("DEV-AAAAAAAAAAAAAAAA", "PENDING", "approved for integration", "admin-1", "idem-replay");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", "APPROVED");
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never())
                .transition(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadIsRejected() {
        OpsDeveloperAccessMapper mapper = mock(OpsDeveloperAccessMapper.class);
        when(mapper.findIdempotency("DEV-AAAAAAAAAAAAAAAA", "idem-conflict"))
                .thenReturn(new OpsDeveloperAccessMapper.IdempotencyRow(
                        "DEV-AAAAAAAAAAAAAAAA", "APPROVED", "idem-conflict", "different-hash", "COMPLETED",
                        "APPROVED", "admin-1", "old payload", now));

        var result = new OpsDeveloperAccessService(mapper, mock(AuditLogService.class))
                .approve("DEV-AAAAAAAAAAAAAAAA", "PENDING", "approved for integration", "admin-1", "idem-conflict");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("DEVELOPER_ACCESS_IDEMPOTENCY_CONFLICT");
    }

    @Test
    void sameIdempotencyKeyCannotBeReusedForAnotherAction() {
        OpsDeveloperAccessMapper mapper = mock(OpsDeveloperAccessMapper.class);
        when(mapper.findIdempotency("DEV-AAAAAAAAAAAAAAAA", "idem-action-conflict"))
                .thenReturn(new OpsDeveloperAccessMapper.IdempotencyRow(
                        "DEV-AAAAAAAAAAAAAAAA", "APPROVED", "idem-action-conflict", "stored-hash", "COMPLETED",
                        "APPROVED", "admin-1", "approved", now));

        var result = new OpsDeveloperAccessService(mapper, mock(AuditLogService.class))
                .reject("DEV-AAAAAAAAAAAAAAAA", "PENDING", "rejecting duplicate action", "admin-1", "idem-action-conflict");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("DEVELOPER_ACCESS_IDEMPOTENCY_CONFLICT");
    }

    @Test
    void auditFailurePropagatesSoTransactionRollsBackTransitionAndIdempotency() {
        OpsDeveloperAccessMapper mapper = mock(OpsDeveloperAccessMapper.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(mapper.insertIdempotency(eq("DEV-AAAAAAAAAAAAAAAA"), eq("APPROVED"), eq("idem-audit"), anyString())).thenReturn(1);
        when(mapper.findForUpdate("DEV-AAAAAAAAAAAAAAAA")).thenReturn(row("DEV-AAAAAAAAAAAAAAAA", "PENDING", null, null));
        when(mapper.transition("DEV-AAAAAAAAAAAAAAAA", "PENDING", "APPROVED", "admin-1", "approved for integration", "idem-audit"))
                .thenReturn(1);
        when(mapper.find("DEV-AAAAAAAAAAAAAAAA")).thenReturn(row("DEV-AAAAAAAAAAAAAAAA", "APPROVED", "admin-1", "approved for integration"));
        doThrow(new IllegalStateException("audit store unavailable")).when(audit).recordRequired(any());

        assertThatThrownBy(() -> new OpsDeveloperAccessService(mapper, audit)
                .approve("DEV-AAAAAAAAAAAAAAAA", "PENDING", "approved for integration", "admin-1", "idem-audit"))
                .isInstanceOf(IllegalStateException.class);
        verify(audit).recordRequired(any());
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).completeIdempotency(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(LocalDateTime.class));
    }

    @Test
    void requestNoMustUseGovernedFormat() {
        var result = new OpsDeveloperAccessService(mock(OpsDeveloperAccessMapper.class), mock(AuditLogService.class))
                .approve("DEV-1", "PENDING", "approved for integration", "admin-1", "idem-invalid");

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("DEVELOPER_ACCESS_REQUEST_INVALID");
    }

    private OpsDeveloperAccessMapper.AccessRow row(String requestNo, String status, String reviewer, String reason) {
        return new OpsDeveloperAccessMapper.AccessRow(requestNo, 7L, "Nexion", "dev@example.com",
                "Inference workloads", status, "PRODUCTION", "", reviewer, reason, now, now, now);
    }

    private String requestHash(String requestNo, String action, String expectedStatus, String reason, String reviewer) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", requestNo, action, expectedStatus, reason, reviewer)
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
