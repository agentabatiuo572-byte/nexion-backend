package ffdd.opsconsole.platform.dto;

import java.util.List;

public record OpsDomainCommandValidationResponse(
        boolean accepted,
        String domainCode,
        String command,
        List<String> enforcedRules,
        String auditAction) {
}
