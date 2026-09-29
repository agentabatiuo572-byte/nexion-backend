package ffdd.opsconsole.content.domain;

public record SupportRules(Long version, Integer dormantDays, Integer maintenanceDays,
        Integer activityWindowDays, String inheritanceMode, Integer maxInheritanceDepth) {}
