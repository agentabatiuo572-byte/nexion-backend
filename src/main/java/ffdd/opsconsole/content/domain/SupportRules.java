package ffdd.opsconsole.content.domain;

public record SupportRules(Long version, Integer dormantDays, Integer maintenanceDays,
        Integer activityWindowDays, String inheritanceMode, Integer maxInheritanceDepth,
        String unboundAssignmentMode, java.time.LocalDateTime modeEffectiveAt) {
    @org.apache.ibatis.annotations.AutomapConstructor
    public SupportRules {}
    public SupportRules(Long version,Integer dormantDays,Integer maintenanceDays,Integer activityWindowDays,
            String inheritanceMode,Integer maxInheritanceDepth) {
        this(version,dormantDays,maintenanceDays,activityWindowDays,inheritanceMode,maxInheritanceDepth,"SUPERVISOR",null);
    }
}
