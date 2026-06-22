package ffdd.opsconsole.content.domain;

public record SessionAdvisorPolicyView(
        boolean enabled,
        int delayMs,
        int cooldownHours,
        int maxPerSession,
        String audience) {
}
