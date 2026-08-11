package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.application.PaymentMethodProviderProperties;
import ffdd.opsconsole.finance.application.PaymentMethodSandboxProfileGuard;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.user.mapper.UserPaymentMethodMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentMethodRevocationSchedulerTest {

    @Test
    void competingWorkersOnlyProcessTheSingleSuccessfulCasClaim() {
        UserPaymentMethodMapper mapper = mock(UserPaymentMethodMapper.class);
        PaymentMethodProviderProperties properties = new PaymentMethodProviderProperties();
        PaymentMethodRevocationFinalizer finalizer = mock(PaymentMethodRevocationFinalizer.class);
        PaymentMethodSandboxProfileGuard guard = mock(PaymentMethodSandboxProfileGuard.class);
        when(guard.sourceEnvironment()).thenReturn("PRODUCTION");
        var command = new UserPaymentMethodMapper.RevokeCommandRow(
                "PMR-ONE", 9L, 42L, "provider-token", 0, LocalDateTime.now().plusMinutes(10), "PRODUCTION");
        when(mapper.claimableRevokeCommands("PRODUCTION")).thenReturn(List.of(command));
        when(mapper.claimRevokeCommand("PMR-ONE", "PRODUCTION")).thenReturn(1, 0);
        when(mapper.releaseRevokeRetry(eq("PMR-ONE"), any(), eq("PRODUCTION"))).thenReturn(1);

        PaymentMethodRevocationScheduler workerA = new PaymentMethodRevocationScheduler(mapper, properties, finalizer, guard);
        PaymentMethodRevocationScheduler workerB = new PaymentMethodRevocationScheduler(mapper, properties, finalizer, guard);
        workerA.process();
        workerB.process();

        verify(mapper, times(2)).claimRevokeCommand("PMR-ONE", "PRODUCTION");
        verify(mapper, times(1)).releaseRevokeRetry(
                "PMR-ONE", "PAYMENT_METHOD_PROVIDER_VERIFIER_UNAVAILABLE", "PRODUCTION");
    }

    @Test
    void requiredAuditFailureEscapesAtomicFinalizerSoSpringRollsBackCommandAndCard() throws Exception {
        UserPaymentMethodMapper mapper = mock(UserPaymentMethodMapper.class);
        AuditLogService audit = mock(AuditLogService.class);
        PaymentMethodRevocationFinalizer finalizer = new PaymentMethodRevocationFinalizer(mapper, audit);
        var command = new UserPaymentMethodMapper.RevokeCommandRow(
                "PMR-TWO", 10L, 43L, "provider-token", 3, LocalDateTime.now().minusSeconds(1), "PRODUCTION");
        when(mapper.finishRevoke("PMR-TWO", "FAILED", null, "provider unavailable", "PRODUCTION")).thenReturn(1);
        when(mapper.updateCardRevokeStatus(10L, "FAILED", "PRODUCTION")).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable"))
                .when(audit).recordRequired(any());

        RecordingTransactionManager transactions = new RecordingTransactionManager();
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> transaction.executeWithoutResult(
                ignored -> finalizer.fail(command, "provider unavailable")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(transactions.commits).isZero();
        verify(mapper).finishRevoke("PMR-TWO", "FAILED", null, "provider unavailable", "PRODUCTION");
        verify(mapper).updateCardRevokeStatus(10L, "FAILED", "PRODUCTION");
        Transactional boundary = PaymentMethodRevocationFinalizer.class
                .getMethod("fail", UserPaymentMethodMapper.RevokeCommandRow.class, String.class)
                .getAnnotation(Transactional.class);
        assertThat(boundary).isNotNull();
        assertThat(boundary.rollbackFor()).contains(Exception.class);
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}
