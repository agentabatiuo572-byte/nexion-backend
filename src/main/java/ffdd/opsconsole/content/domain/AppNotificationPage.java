package ffdd.opsconsole.content.domain;

import java.util.List;
import java.util.Map;

public record AppNotificationPage(
        List<AppNotificationView> items,
        String nextCursor,
        long unread,
        Map<String, Long> unreadByKind) {
}
