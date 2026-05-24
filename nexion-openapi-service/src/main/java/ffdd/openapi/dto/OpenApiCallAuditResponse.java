package ffdd.openapi.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OpenApiCallAuditResponse {
    private Long id;
    private Long appId;
    private String appKey;
    private String apiPath;
    private String httpMethod;
    private String nonce;
    private String requestHash;
    private Integer responseCode;
    private String responseMessage;
    private Long costMs;
    private LocalDateTime createdAt;
}
