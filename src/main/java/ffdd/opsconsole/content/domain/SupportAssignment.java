package ffdd.opsconsole.content.domain;

public record SupportAssignment(Long id, Long customerId, Long agentAdminId, Long version,
        String source, Long segmentRootId, Integer depth, Long parentAssignmentId, Long ruleVersion) {}
