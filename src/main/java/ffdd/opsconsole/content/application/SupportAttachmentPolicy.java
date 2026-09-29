package ffdd.opsconsole.content.application;

import java.util.List;
import java.util.Set;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** No production thresholds: all four limits must be explicitly configured. */
@Data
@Component
@ConfigurationProperties(prefix = "nexion.support.attachments")
public class SupportAttachmentPolicy {
    private List<String> allowedMimeTypes;
    private Long maxBytes;
    private Long maxPixels;
    private Long ttlSeconds;

    public View view() {
        boolean configured = allowedMimeTypes != null && !allowedMimeTypes.isEmpty()
                && Set.of("image/png", "image/jpeg").containsAll(allowedMimeTypes)
                && maxBytes != null && maxBytes > 0 && maxBytes < Integer.MAX_VALUE
                && maxPixels != null && maxPixels > 0 && maxPixels <= Integer.MAX_VALUE
                && ttlSeconds != null && ttlSeconds > 0 && ttlSeconds <= Integer.MAX_VALUE;
        return new View(configured, configured ? List.copyOf(allowedMimeTypes) : List.of(),
                configured ? maxBytes : null, configured ? maxPixels : null,
                configured ? ttlSeconds : null, configured ? null : "ATTACHMENT_POLICY_UNCONFIGURED");
    }

    public record View(boolean available, List<String> allowedMimeTypes, Long maxBytes,
            Long maxPixels, Long ttlSeconds, String unavailableReason) {}
}
