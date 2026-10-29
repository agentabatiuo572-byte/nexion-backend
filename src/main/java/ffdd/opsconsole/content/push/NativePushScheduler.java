package ffdd.opsconsole.content.push;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="native-push", name="scheduler-enabled", havingValue="true")
public class NativePushScheduler {
    private final NativePushDispatcher dispatcher;
    public NativePushScheduler(NativePushDispatcher dispatcher) { this.dispatcher = dispatcher; }
    @Scheduled(fixedDelayString="${native-push.dispatch-delay-ms:15000}")
    public void dispatch() { dispatcher.tick(); }
}
