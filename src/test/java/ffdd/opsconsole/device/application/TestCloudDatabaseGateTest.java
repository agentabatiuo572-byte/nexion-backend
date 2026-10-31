package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TestCloudDatabaseGateTest {
    @Test void lazyHikariDefaultsAreResolvedWithoutOpeningThePool() {
        try(var source=new com.zaxxer.hikari.HikariDataSource()) {
            source.setJdbcUrl("jdbc:mysql://127.0.0.1:1/unopened");
            new TestCloudDatabaseGate(source);
            assertThat(source.getMaximumPoolSize()).isEqualTo(10);
            assertThat(source.getHikariPoolMXBean()).isNull();
        }
    }
    @Test void halfPoolIsAdmittedBeforeBorrowingAndPermitsReturnAfterFailure() throws Exception {
        var gate=new TestCloudDatabaseGate(5); var pool=Executors.newFixedThreadPool(3);
        var entered=new CountDownLatch(2); var release=new CountDownLatch(1); var running=new AtomicInteger();
        try {
            Callable<Integer> work=()->gate.call(()->{
                int count=running.incrementAndGet(); entered.countDown();
                try { assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); }
                catch(InterruptedException failed) { throw new RuntimeException(failed); }
                finally { running.decrementAndGet(); }
                return count;
            });
            var first=pool.submit(work); var second=pool.submit(work);
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var waiting=pool.submit(work);
            assertThat(running).hasValue(2); assertThat(waiting.isDone()).isFalse(); release.countDown();
            assertThat(first.get()).isBetween(1,2); assertThat(second.get()).isBetween(1,2); assertThat(waiting.get()).isBetween(1,2);
            assertThatThrownBy(()->gate.call(()->{ throw new IllegalStateException("FAIL"); })).hasMessage("FAIL");
            assertThat(gate.call(()->"returned")).isEqualTo("returned");
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
