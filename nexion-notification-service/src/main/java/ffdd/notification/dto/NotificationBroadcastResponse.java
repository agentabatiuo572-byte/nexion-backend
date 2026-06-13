package ffdd.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationBroadcastResponse {
    private String campaignName;
    private int requestedUsers;
    private int targetUsers;
    private int createdRows;
}
