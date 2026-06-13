package ffdd.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class NotificationBroadcastRequest {
    @NotBlank
    @Size(max = 64)
    private String campaignName;

    @NotEmpty
    @Size(max = 500)
    private List<Long> targetUserIds;

    @NotBlank
    @Size(max = 32)
    private String type;

    @NotBlank
    @Size(max = 128)
    private String title;

    @NotBlank
    @Size(max = 512)
    private String body;
}
