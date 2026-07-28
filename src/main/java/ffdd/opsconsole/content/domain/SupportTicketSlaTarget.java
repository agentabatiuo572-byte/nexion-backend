package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;

public record SupportTicketSlaTarget(
        Long ruleVersion,
        Integer firstResponseMins,
        Integer resolutionHours,
        String queue,
        String escalation,
        LocalDateTime firstResponseDeadlineAt,
        LocalDateTime resolutionDeadlineAt,
        LocalDateTime firstResponseAt,
        LocalDateTime resolvedAt,
        boolean firstResponseOverdue,
        boolean resolutionOverdue,
        LocalDateTime evaluatedAt) {
}
