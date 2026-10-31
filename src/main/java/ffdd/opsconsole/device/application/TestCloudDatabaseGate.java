package ffdd.opsconsole.device.application;

import com.zaxxer.hikari.HikariDataSource;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** Admission precedes transaction creation, so waiters never borrow a pool connection. */
@Component
public class TestCloudDatabaseGate {
    private final Semaphore permits;
    @org.springframework.beans.factory.annotation.Autowired
    public TestCloudDatabaseGate(DataSource source) {
        this(poolSize(source));
    }
    TestCloudDatabaseGate(int maximumPoolSize) { permits=new Semaphore(Math.max(1,maximumPoolSize/2),true); }
    private static int poolSize(DataSource source) {
        try {
            HikariDataSource hikari=source.unwrap(HikariDataSource.class);
            // Hikari resolves its default (-1 -> 10) during validation, before any connection is opened.
            if(hikari.getMaximumPoolSize()<1) hikari.validate();
            return hikari.getMaximumPoolSize();
        }
        catch(java.sql.SQLException invalid) { throw new IllegalStateException("TEST_CLOUD_HIKARI_POOL_REQUIRED",invalid); }
    }
    public <T> T call(Supplier<T> work) {
        try { permits.acquire(); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("TEST_CLOUD_DB_ADMISSION_INTERRUPTED"); }
        try { return work.get(); } finally { permits.release(); }
    }
}
