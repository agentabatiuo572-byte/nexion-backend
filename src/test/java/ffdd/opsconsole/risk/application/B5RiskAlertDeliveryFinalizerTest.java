package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.risk.mapper.B5RiskRadarMapper;
import ffdd.opsconsole.risk.mapper.B5RiskRadarMapper.AlertDeliveryRecord;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class B5RiskAlertDeliveryFinalizerTest {
    @Test
    void requiredAuditFailureEscapesTheRequiresNewTransactionInsteadOfLeavingDeliveredWithoutAudit() throws Exception {
        B5RiskRadarMapper mapper = mock(B5RiskRadarMapper.class);
        AuditLogService audit = mock(AuditLogService.class);
        B5RiskAlertDeliveryFinalizer finalizer = new B5RiskAlertDeliveryFinalizer(mapper, audit);
        AlertDeliveryRecord row = new AlertDeliveryRecord(3L, "SIG-3", "admin:7", "inApp", "SENDING", 0, LocalDateTime.now());
        when(mapper.markAlertDelivered(3L, "durable-inbox", "inapp:3")).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit down")).when(audit).recordRequired(any());

        assertThatThrownBy(() -> finalizer.complete(row, "durable-inbox", "inapp:3"))
                .isInstanceOf(IllegalStateException.class).hasMessage("audit down");
        verify(mapper).markAlertDelivered(3L, "durable-inbox", "inapp:3");

        Transactional tx = B5RiskAlertDeliveryFinalizer.class.getMethod(
                "complete", AlertDeliveryRecord.class, String.class, String.class).getAnnotation(Transactional.class);
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(tx.rollbackFor()).contains(Exception.class);
    }
}
