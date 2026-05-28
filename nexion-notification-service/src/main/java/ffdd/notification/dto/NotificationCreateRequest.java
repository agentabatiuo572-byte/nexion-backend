package ffdd.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class NotificationCreateRequest {
    @Size(max = 128)
    private String bizNo;

    @NotNull
    private Long userId;

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
