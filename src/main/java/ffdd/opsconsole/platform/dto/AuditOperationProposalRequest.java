package ffdd.opsconsole.platform.dto;

import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;

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
        String sourceDomain,
        AuditReplayCommand command,
        AuditLockTarget target) {
}
