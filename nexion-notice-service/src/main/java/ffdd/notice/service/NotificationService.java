package ffdd.notice.service;

import ffdd.notice.domain.Notification;
import java.util.List;

public interface NotificationService {
    List<Notification> listMine();
}

