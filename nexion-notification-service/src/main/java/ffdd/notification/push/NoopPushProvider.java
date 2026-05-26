package ffdd.notification.push;

import ffdd.notification.domain.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class NoopPushProvider implements PushProvider {
    private static final Logger log = LoggerFactory.getLogger(NoopPushProvider.class);

    @Override
    public void push(Notification notification) {
        log.info("Noop notification push notificationId={}, userId={}, type={}",
                notification.getId(), notification.getUserId(), notification.getType());
    }
}
