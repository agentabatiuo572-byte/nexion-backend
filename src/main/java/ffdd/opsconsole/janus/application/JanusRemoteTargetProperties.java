package ffdd.opsconsole.janus.application;

import java.util.List;
import java.util.Locale;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Data
@Component
@ConfigurationProperties(prefix = "nexion.janus.remote-target")
public class JanusRemoteTargetProperties {
    /**
     * Exact HTTPS origins approved by deployment configuration.
     * Empty is intentional and fails closed until an operator configures trusted origins.
     */
    private List<String> allowedOrigins = List.of();

    public boolean allows(String origin) {
        if (!StringUtils.hasText(origin) || allowedOrigins == null) return false;
        String expected = trimTrailingSlash(origin.trim().toLowerCase(Locale.ROOT));
        return normalizedAllowedOrigins().stream()
                .anyMatch(expected::equals);
    }

    public List<String> normalizedAllowedOrigins() {
        if (allowedOrigins == null) return List.of();
        return allowedOrigins.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .map(this::trimTrailingSlash)
                .distinct()
                .sorted()
                .toList();
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
