package ffdd.commerce.dto;

import lombok.Data;

@Data
public class ProductMediaUploadResponse {
    private String bucket;
    private String objectKey;
    private String downloadUrl;
    private String contentType;
    private long sizeBytes;
}
