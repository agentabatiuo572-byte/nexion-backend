package ffdd.opsconsole.content.push;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component("nativePush")
public class NativePushHealthIndicator implements HealthIndicator {
    private final DirectPushClient provider;
    private final Environment env;
    public NativePushHealthIndicator(DirectPushClient provider, Environment env) { this.provider = provider; this.env = env; }
    public Health health() {
        String state = provider.configurationState();
        boolean scheduled = env.getProperty("native-push.scheduler-enabled", Boolean.class, false);
        // UNKNOWN does not break the entire existing application health while this optional channel is intentionally unopened.
        return ("CONFIGURED".equals(provider.configurationState("FCM")) && "CONFIGURED".equals(provider.configurationState("APNS")) && scheduled ? Health.up() : Health.unknown())
                .withDetail("channel", state).withDetail("schedulerEnabled", scheduled)
                .withDetail("receiptMeaning", "provider acceptance only; device delivery not asserted").build();
    }
}
