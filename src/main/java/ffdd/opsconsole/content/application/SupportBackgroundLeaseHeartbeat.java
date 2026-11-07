package ffdd.opsconsole.content.application;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/** Renews only owned Redis leases; database work stays on the caller's transaction thread. */
@Service
public class SupportBackgroundLeaseHeartbeat {
    private final ScheduledThreadPoolExecutor executor=new ScheduledThreadPoolExecutor(1,task->{
        Thread thread=new Thread(task,"support-lease-heartbeat");thread.setDaemon(true);return thread;
    });

    public SupportBackgroundLeaseHeartbeat() {executor.setRemoveOnCancelPolicy(true);}

    public Lease start(Runnable ownedRenew) {
        var lease=foreground(ownedRenew);
        lease.future=executor.scheduleWithFixedDelay(lease::renew,30,30,TimeUnit.SECONDS);
        return lease;
    }

    // Existing manually constructed callers keep foreground renewal without creating an unmanaged executor.
    static Lease foreground(Runnable ownedRenew) {return new Lease(ownedRenew);}

    @PreDestroy public void stop() {executor.shutdownNow();}

    public static final class Lease implements AutoCloseable {
        private final Runnable ownedRenew;
        private ScheduledFuture<?> future;
        private RuntimeException failure;
        private boolean stopped;
        private Lease(Runnable ownedRenew) {this.ownedRenew=ownedRenew;}

        public synchronized void renew() {
            if(failure!=null)throw failure;
            if(stopped)return;
            try {ownedRenew.run();}
            catch(RuntimeException ex) {failure=ex;throw ex;}
        }

        /** Waits for an in-flight renewal before the caller's final owned publish check. */
        public synchronized void finish() {
            close();
            if(failure!=null)throw failure;
        }

        @Override public synchronized void close() {
            stopped=true;
            if(future!=null)future.cancel(false);
        }
    }
}
