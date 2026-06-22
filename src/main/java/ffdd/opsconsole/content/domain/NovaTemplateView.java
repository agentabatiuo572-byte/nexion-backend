package ffdd.opsconsole.content.domain;

public record NovaTemplateView(
        String channel,
        String name,
        String cta,
        String version,
        String status) {
}
