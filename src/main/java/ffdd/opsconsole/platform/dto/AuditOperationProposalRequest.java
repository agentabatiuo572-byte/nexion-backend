package ffdd.opsconsole.platform.dto;

public record AuditOperationProposalRequest(
        String action,
        String obj,
        String beforeValue,
        String afterValue,
        String operator,
        String operatorRole,
        String type,
        Boolean amplifies,
        Boolean sos,
        String roleGate,
        String reason,
        String sourceDomain) {
}
