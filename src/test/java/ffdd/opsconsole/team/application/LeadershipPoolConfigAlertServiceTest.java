package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class LeadershipPoolConfigAlertServiceTest {

    @Test
    void missingAuthoritativeConfigWritesRequiredAuditAndCanonicalOutboxEvidence() {
        AuditLogService auditLogService = Mockito.mock(AuditLogService.class);
        EventOutboxService eventOutboxService = Mockito.mock(EventOutboxService.class);
        LeadershipPoolConfigAlertService service =
                new LeadershipPoolConfigAlertService(auditLogService, eventOutboxService);
        LeadershipPoolConfigGuard.ConfigUnavailableException failure =
                new LeadershipPoolConfigGuard.ConfigUnavailableException(
                        "team.ui.F.pool.ratio", "MISSING", "absent");

        service.recordBlocked(failure, "scheduler");

        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(audit.capture());
        assertThat(audit.getValue())
                .extracting(AuditLogWriteRequest::getAction, AuditLogWriteRequest::getResourceId,
                        AuditLogWriteRequest::getResult, AuditLogWriteRequest::getRiskLevel)
                .containsExactly("F4_LEADERSHIP_POOL_CONFIG_BLOCKED", "team.ui.F.pool.ratio", "FAILED", "HIGH");
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) audit.getValue().getDetail();
        assertThat(detail)
                .containsEntry("source", "scheduler")
                .containsEntry("configKey", "team.ui.F.pool.ratio")
                .containsEntry("reason", "MISSING")
                .containsEntry("valueFingerprint", "absent")
                .containsKey("blockedAt");
        verify(eventOutboxService).publish(
                eq("LEADERSHIP_POOL_CONFIG"), eq("absent"),
                eq("leadership_pool.settlement_blocked"), eq(audit.getValue().getDetail()));
    }
}
