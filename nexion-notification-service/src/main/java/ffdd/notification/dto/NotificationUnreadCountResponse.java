package ffdd.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationUnreadCountResponse {
    private Long userId;
    private long unreadCount;
    private String cacheStatus;
}
