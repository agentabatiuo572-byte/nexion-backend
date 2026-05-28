package ffdd.system.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationCreateRequest {
    private String bizNo;
    private Long userId;
    private String type;
    private String title;
    private String body;
}
