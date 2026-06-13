package ffdd.openapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WebhookCreateRequest {
    @NotNull
    private Long appId;

    @NotBlank
    private String eventType;

    @Size(max = 512)
    private String callbackUrl;

    @Size(max = 8)
    private String callbackScheme;

    @Size(max = 255)
    private String callbackHost;

    @Min(1)
    @Max(65535)
    private Integer callbackPort;

    @Size(max = 512)
    private String callbackPath;

    @Size(max = 512)
    private String callbackQuery;
}
