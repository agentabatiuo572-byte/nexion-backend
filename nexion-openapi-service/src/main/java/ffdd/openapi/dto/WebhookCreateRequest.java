package ffdd.openapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class WebhookCreateRequest {
    @NotNull
    private Long appId;

    @NotBlank
    private String eventType;

    @NotBlank
    private String callbackUrl;
}
