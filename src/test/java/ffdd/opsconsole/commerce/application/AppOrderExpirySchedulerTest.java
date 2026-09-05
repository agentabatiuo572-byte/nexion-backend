package ffdd.opsconsole.commerce.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.commerce.mapper.AppOrderCommandMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AppOrderExpirySchedulerTest {
    @Test
    void expiresEveryEligibleCandidateUsingAnIndependentTransactionalServiceCall() {
        var mapper = mock(AppOrderCommandMapper.class);
        var service = mock(AppOrderCommandService.class);
        when(mapper.expiredPendingOrders(30, 100)).thenReturn(List.of(
                new AppOrderCommandMapper.PendingOrderExpiryCandidate("ORD-1", 7L),
                new AppOrderCommandMapper.PendingOrderExpiryCandidate("ORD-2", 8L)));
        var scheduler = new AppOrderExpiryScheduler(mapper, service, 30, 100);

        scheduler.expirePendingOrders();

        verify(service).expirePendingOrder(7L, "ORD-1");
        verify(service).expirePendingOrder(8L, "ORD-2");
    }
}
