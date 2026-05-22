package ffdd.openapi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OpenApiAppCreateResponse {
    private Long id;
    private String appName;
    private String appKey;
    private String appSecret;
}
