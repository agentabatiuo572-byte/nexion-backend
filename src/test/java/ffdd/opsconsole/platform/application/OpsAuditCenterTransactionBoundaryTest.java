package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.PlatformConfigRepository;
import ffdd.opsconsole.platform.dto.AuditCenterOverview;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
import ffdd.opsconsole.platform.mapper.AuditConfirmCategoryMapper;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.platform.mapper.AuditOperationHistoryMapper;
import ffdd.opsconsole.platform.mapper.AuditOperationTicketMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.support.TransactionTemplate;

class OpsAuditCenterTransactionBoundaryTest {

    @Test
    void proposalReplayReturnsStoredTicketAndPayloadMismatchRemainsStable409() {
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
        AtomicReference<String> storedHash = new AtomicReference<>();
        AuditCenterOverview.AuditOperationTicket storedTicket = new AuditCenterOverview.AuditOperationTicket(
                "WO-1", "SKU status", "sku-1", "pending", "on", "superadmin", "商品运营",
                "param", false, false, "now", true, "门槛者", "符合上架节奏要求", "pending");
        doAnswer(invocation -> new TransactionTemplate(transactionManager).execute(status -> {
            String requestHash = invocation.getArgument(2);
            if (storedHash.compareAndSet(null, requestHash) || storedHash.get().equals(requestHash)) {
                return storedTicket;
            }
            throw new BizException(409, "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
        })).when(idempotencyService).execute(
                eq("A2_COMMAND"), eq("same-command-key"), any(), eq(AuditCenterOverview.AuditOperationTicket.class), any());

        OpsAuditCenterService target = new OpsAuditCenterService(
                mock(PlatformConfigRepository.class),
                mock(AuditLogService.class),
                mock(OpsReadTimeSeedPolicy.class),
                mock(AuditOperationTicketMapper.class),
                mock(AuditOperationHistoryMapper.class),
                mock(AuditConfirmCategoryMapper.class),
                mock(AuditObjectLockMapper.class),
                mock(AuditReplayBusinessPermissionGuard.class),
                mock(AuditReplayDispatcher.class),
                new ObjectMapper().findAndRegisterModules(),
                idempotencyService);
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        OpsAuditCenterService service = (OpsAuditCenterService) proxyFactory.getProxy();

        AuditOperationProposalRequest original = proposal("on", "符合上架节奏要求");
        AuditOperationProposalRequest changed = proposal("off", "调整为下架避免提前释放");

        ApiResult<AuditCenterOverview.AuditOperationTicket> first =
                service.createProposal("same-command-key", original);
        ApiResult<AuditCenterOverview.AuditOperationTicket> replay =
                service.createProposal("same-command-key", original);
        ApiResult<AuditCenterOverview.AuditOperationTicket> mismatch =
                service.createProposal("same-command-key", changed);

        assertThat(first.getCode()).isZero();
        assertThat(replay.getData().id()).isEqualTo(first.getData().id());
        assertThat(mismatch.getCode()).isEqualTo(409);
        assertThat(mismatch.getMessage()).isEqualTo("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
    }

    private AuditOperationProposalRequest proposal(String afterValue, String reason) {
        return new AuditOperationProposalRequest(
                "SKU status", "sku-1", "pending", afterValue, "superadmin", "商品运营", "param",
                false, false, "门槛者", reason, "E1",
                new AuditReplayCommand("E", "e1_sku_status", Map.of("skuId", "sku-1", "status", afterValue)),
                new AuditLockTarget("E", "device_sku", "sku-1"), null);
    }

    private static final class TrackingTransactionManager extends AbstractPlatformTransactionManager {
        private final TxObject transaction = new TxObject();

        @Override
        protected Object doGetTransaction() {
            return transaction;
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return ((TxObject) transaction).active;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            TxObject current = (TxObject) transaction;
            current.active = true;
            current.rollbackOnly = false;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            TxObject current = (TxObject) status.getTransaction();
            current.active = false;
            current.rollbackOnly = false;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            TxObject current = (TxObject) status.getTransaction();
            current.active = false;
            current.rollbackOnly = false;
        }

        @Override
        protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            ((TxObject) status.getTransaction()).rollbackOnly = true;
        }
    }

    private static final class TxObject implements SmartTransactionObject {
        private boolean active;
        private boolean rollbackOnly;

        @Override
        public boolean isRollbackOnly() {
            return rollbackOnly;
        }

        @Override
        public void flush() {
        }
    }
}
