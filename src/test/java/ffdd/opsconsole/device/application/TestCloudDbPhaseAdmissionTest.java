package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import ffdd.opsconsole.device.mapper.TestCloudExecutorMapper.Execution;
import ffdd.opsconsole.device.mapper.TestCloudRequestMapper;
import java.sql.Connection;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

class TestCloudDbPhaseAdmissionTest {
    @Test void heartbeatManifestAndReceiptWaitBeforeTheirRealTransactionBorrowsAConnection() throws Exception {
        var borrowed=new AtomicInteger(); var active=new AtomicInteger(); var maximum=new AtomicInteger();
        var source=mock(DataSource.class);
        when(source.getConnection()).thenAnswer(call->{
            borrowed.incrementAndGet(); maximum.accumulateAndGet(active.incrementAndGet(),Math::max);
            var connection=mock(Connection.class);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            doAnswer(close->{ active.decrementAndGet(); return null; }).when(connection).close();
            return connection;
        });
        var manager=new DataSourceTransactionManager(source);
        var attempting=new CountDownLatch(3); var entries=new AtomicInteger();
        var gate=new TestCloudDatabaseGate(2) {
            @Override public <T> T call(Supplier<T> work) {
                if(entries.incrementAndGet()>1) attempting.countDown();
                return super.call(work);
            }
        };
        var requests=new TestCloudRequests(mock(TestCloudRequestMapper.class),gate,
                Clock.fixed(TestCloudExecutorServiceTest.INSTANT,ZoneOffset.UTC),manager);
        var fixture=new TestCloudExecutorServiceTest(); fixture.setup();
        ReflectionTestUtils.setField(fixture.service,"requests",requests);
        String task=TestCloudExecutorServiceTest.TASK,nextKey="BGE-NEXT-"+"d".repeat(32);
        var e=TestCloudExecutorServiceTest.execution(null,null,null);
        when(fixture.mapper.lock(task)).thenReturn(new Execution(e.taskNo(),e.executorId(),e.ownerId(),e.deviceId(),e.instanceNo(),
                e.taskConfigId(),TestCloudExecutorService.sha256(nextKey),e.claimToken(),e.inputJson(),e.inputHash(),e.modelTag(),e.modelDigest(),
                e.proofNonce(),null,null,null,null,e.createdAt()));
        when(fixture.assignments.lockActiveAssignment(7L,11L,"PRODUCTION")).thenReturn(TestCloudExecutorServiceTest.task("RUNNING"));
        when(fixture.mapper.countActive(7,11)).thenReturn(1);
        when(fixture.mapper.heartbeat(eq(11L),eq(task),anyString(),any())).thenReturn(1);
        var proxy=new ProxyFactory(fixture.service); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        var service=(TestCloudExecutorService)proxy.getProxy();
        var pool=Executors.newFixedThreadPool(4); var held=new CountDownLatch(1); var release=new CountDownLatch(1);
        try {
            var holder=pool.submit(()->requests.db(()->{
                held.countDown();
                try { assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); }
                catch(InterruptedException failure) { throw new RuntimeException(failure); }
                return true;
            }));
            assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
            var heartbeat=pool.submit(()->service.heartbeat(fixture.identity,null,null));
            var manifest=pool.submit(()->service.manifest(fixture.identity,task,nextKey));
            var receipt=pool.submit(()->service.receipt(fixture.identity,task));
            assertThat(attempting.await(2,TimeUnit.SECONDS)).as("All phases must enter admission before getConnection; borrowed=%s",borrowed.get()).isTrue();
            assertThat(borrowed).hasValue(1);
            assertThat(heartbeat.isDone()).isFalse(); assertThat(manifest.isDone()).isFalse(); assertThat(receipt.isDone()).isFalse();
            release.countDown(); assertThat(holder.get(5,TimeUnit.SECONDS)).isTrue();
            assertThat(heartbeat.get(5,TimeUnit.SECONDS).getCode()).isZero();
            assertThat(manifest.get(5,TimeUnit.SECONDS).getCode()).isZero();
            assertThat(receipt.get(5,TimeUnit.SECONDS).getCode()).isZero();
            assertThat(borrowed).hasValue(4); assertThat(maximum).hasValue(1); assertThat(active).hasValue(0);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
