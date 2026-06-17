package ffdd.opsconsole.platform.dto;

import java.util.List;

public record OpsDomainRuntimeContract(
        String domainCode,
        String domainName,
        String adminApiPrefix,
        String ownerPackage,
        List<String> activeCapabilities,
        List<ApiFamilyContract> apiFamilies,
        List<String> requiredWriteHeaders,
        boolean auditRequired,
        List<String> redlines,
        List<String> updateCorrections,
        List<String> sunsetCapabilities,
        String migrationStatus) {
}
