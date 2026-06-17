package ffdd.opsconsole.platform.dto;

import java.util.List;

public record OpsDomainSummary(
        String code,
        String displayName,
        String packageName,
        String adminApiPrefix,
        List<String> activeCapabilities) {
    public OpsDomainSummary {
        activeCapabilities = List.copyOf(activeCapabilities);
    }
}
