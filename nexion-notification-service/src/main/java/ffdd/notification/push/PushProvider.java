package ffdd.notification.push;

import ffdd.notification.domain.Notification;

public interface PushProvider {
    void push(Notification notification);
}
