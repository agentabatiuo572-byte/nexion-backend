package ffdd.opsconsole.platform.dto;

import java.util.List;

public record OpsArchitectureOverview(
        String deploymentMode,
        List<String> springBootApplications,
        int domainCount,
        List<OpsDomainSummary> domains,
        List<String> deprecatedCapabilities,
        List<String> boundaryRules) {
    public OpsArchitectureOverview {
        springBootApplications = List.copyOf(springBootApplications);
        domains = List.copyOf(domains);
        deprecatedCapabilities = List.copyOf(deprecatedCapabilities);
        boundaryRules = List.copyOf(boundaryRules);
    }
}
