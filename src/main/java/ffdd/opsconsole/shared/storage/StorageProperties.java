package ffdd.opsconsole.shared.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "nexion.storage")
public class StorageProperties {
    private String endpoint = "http://127.0.0.1:9000";
    private String accessKey = "";
    private String secretKey = "";
    private String bucket = "nexion";
}
