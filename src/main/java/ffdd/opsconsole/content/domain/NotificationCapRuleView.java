package ffdd.opsconsole.content.domain;

public record NotificationCapRuleView(
        String tier,
        String cap,
        String policy,
        boolean locked) {
}
