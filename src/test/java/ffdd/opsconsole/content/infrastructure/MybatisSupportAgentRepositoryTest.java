package ffdd.opsconsole.content.infrastructure;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.mapper.SupportAgentMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MybatisSupportAgentRepositoryTest {

    @Test
    void concurrentRequestsInitializeAndBackfillSchemaOnlyOnce() throws Exception {
        SupportAgentMapper mapper = Mockito.mock(SupportAgentMapper.class);
        when(mapper.countSeatTypeColumn()).thenReturn(1L);
        when(mapper.countAssignmentTypeColumn()).thenReturn(0L);
        when(mapper.countActiveUserColumn()).thenReturn(1L);
        when(mapper.countActiveUserUniqueIndex()).thenReturn(1L);
        MybatisSupportAgentRepository repository = new MybatisSupportAgentRepository(mapper);

        int callers = 8;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < callers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    repository.ensureSchema();
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        verify(mapper, times(1)).createProfileTable();
        verify(mapper, times(1)).backfillSeatType();
        verify(mapper, times(1)).createAssignmentTable();
        verify(mapper, times(1)).deactivateDuplicateActiveAssignments();
    }
}
