package ffdd.opsconsole.content.domain;

/** Current responsibility, independent of historical conversation owners and socket presence. */
public record AppSupportAdvisorView(Long assignmentId, Long currentAdvisorId, String currentAdvisorName,
                                    String assignmentState, String availability) {
    public static AppSupportAdvisorView unbound() {
        return new AppSupportAdvisorView(null, null, null, "UNBOUND", "UNBOUND");
    }
}
