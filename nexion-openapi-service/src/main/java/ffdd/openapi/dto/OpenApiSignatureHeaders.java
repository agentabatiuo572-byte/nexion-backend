package ffdd.openapi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OpenApiSignatureHeaders {
    private String appKey;
    private String timestamp;
    private String nonce;
    private String signature;
}
