package ffdd.opsconsole.platform.dto;

public record OpsDomainCommandValidationRequest(
        String command,
        String resource,
        String fromStatus,
        String toStatus,
        Double coverageRatio,
        Double coverageRedline,
        String reason) {
}
