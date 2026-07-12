package ffdd.opsconsole.content.domain;

import java.util.List;

public record AppNotificationPage(
        List<AppNotificationView> items,
        String nextCursor,
        long unread) {
}
