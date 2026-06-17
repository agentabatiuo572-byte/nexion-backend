package ffdd.opsconsole.common.domain;

import java.util.List;

public record OpsDomainDescriptor(
        DomainCode code,
        String packageName,
        String adminApiPrefix,
        List<String> activeCapabilities) {
    public OpsDomainDescriptor {
        activeCapabilities = List.copyOf(activeCapabilities);
    }
}
