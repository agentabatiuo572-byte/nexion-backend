package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.domain.VRankSkuFulfillmentRow;
import ffdd.opsconsole.team.mapper.TeamFulfillmentQueueMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class VRankSkuFulfillmentServiceTest {
    private final TeamFulfillmentQueueMapper mapper = mock(TeamFulfillmentQueueMapper.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final VRankSkuFulfillmentService service = new VRankSkuFulfillmentService(mapper, tx, audit, outbox);
    private final VRankSkuFulfillmentRow row = new VRankSkuFulfillmentRow(7L, 21L, "V5", "rack-p1", "PENDING");

    @BeforeEach
    void transactionManager() {
        when(tx.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
    }

    @Test
    void grantsOnlyAfterStockReservationEntitlementReadbackAndPayoutCas() {
        when(mapper.pendingSkuFulfillments(25)).thenReturn(java.util.List.of(row));
        when(mapper.claimSkuFulfillment(7L)).thenReturn(1);
        when(mapper.reserveSkuStock("rack-p1")).thenReturn(1);
        when(mapper.insertSkuEntitlement(7L, 21L, "rack-p1", "V5")).thenReturn(1);
        when(mapper.countGrantedSkuEntitlement(7L, 21L, "rack-p1")).thenReturn(1);
        when(mapper.grantSkuPayout(21L, "V5", "rack-p1")).thenReturn(1);
        when(mapper.completeSkuFulfillment(7L)).thenReturn(1);

        assertThat(service.processPending(25)).isEqualTo(1);
        assertThat(service.hasGrantedEntitlement(7L, 21L, "rack-p1")).isTrue();
        verify(mapper).reserveSkuStock("rack-p1");
        verify(mapper).grantSkuPayout(21L, "V5", "rack-p1");
        verify(mapper).completeSkuFulfillment(7L);
        verify(audit).recordRequired(any());
        verify(outbox).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void stockFailureLeavesPayoutPendingAndMarksQueueRetryableFailed() {
        when(mapper.pendingSkuFulfillments(25)).thenReturn(java.util.List.of(row));
        when(mapper.claimSkuFulfillment(7L)).thenReturn(1);
        when(mapper.reserveSkuStock("rack-p1")).thenReturn(0);
        when(mapper.failSkuFulfillment(anyLong(), anyString())).thenReturn(1);

        assertThat(service.processPending(25)).isZero();
        verify(mapper).failSkuFulfillment(7L, "SKU_OUT_OF_STOCK_OR_INACTIVE");
        verify(mapper, never()).grantSkuPayout(anyLong(), anyString(), anyString());
        verify(mapper, never()).completeSkuFulfillment(anyLong());
        verify(audit, never()).recordRequired(any());
        verify(outbox, never()).publish(anyString(), anyString(), anyString(), any());
    }
}
