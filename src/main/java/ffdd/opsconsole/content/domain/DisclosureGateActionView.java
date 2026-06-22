package ffdd.opsconsole.content.domain;

public record DisclosureGateActionView(
        String key,
        String name,
        String sub,
        String status,
        String tone,
        boolean active) {
}
