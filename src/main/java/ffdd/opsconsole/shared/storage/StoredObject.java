package ffdd.opsconsole.shared.storage;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class StoredObject {
    private String bucket;
    private String objectKey;
    private String contentType;
    private long sizeBytes;
}
