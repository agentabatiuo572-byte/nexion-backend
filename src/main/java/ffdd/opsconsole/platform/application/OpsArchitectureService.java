package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.common.domain.OpsDomainCatalog;
import ffdd.opsconsole.platform.dto.OpsArchitectureOverview;
import ffdd.opsconsole.platform.dto.OpsDomainSummary;
import java.util.List;

@ApplicationService
public class OpsArchitectureService {
    public OpsArchitectureOverview overview() {
        List<OpsDomainSummary> domains = OpsDomainCatalog.activeDomains().stream()
                .map(domain -> new OpsDomainSummary(
                        domain.code().name(),
                        domain.code().displayName(),
                        domain.packageName(),
                        domain.adminApiPrefix(),
                        domain.activeCapabilities()))
                .toList();

        return new OpsArchitectureOverview(
                "MODULAR_MONOLITH",
                List.of("nexion-backend"),
                domains.size(),
                domains,
                OpsDomainCatalog.deprecatedCapabilities(),
                List.of(
                        "Controller -> same-domain ApplicationService only",
                        "Cross-domain calls -> public Facade or DomainEvent only",
                        "No Controller -> Mapper/Entity access",
                        "No cross-domain Mapper/Entity access"));
    }
}
