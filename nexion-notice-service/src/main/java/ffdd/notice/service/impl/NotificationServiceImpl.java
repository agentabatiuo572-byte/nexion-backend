package ffdd.notice.service.impl;

import ffdd.notice.domain.Notification;
import ffdd.notice.service.NotificationService;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class NotificationServiceImpl implements NotificationService {
    @Override
    public List<Notification> listMine() {
        Notification item = new Notification();
        item.setId(1L);
        item.setUserId(10001L);
        item.setType("EARNING");
        item.setTitle("Overnight compute completed");
        item.setBody("+6.42 USDT is ready.");
        item.setReadFlag(0);
        return List.of(item);
    }
}

