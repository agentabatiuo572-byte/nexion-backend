package ffdd.opsconsole.content.domain;

public record NovaEventDrivenView(
        String name,
        String reason,
        String owner,
        String tone,
        String status) {
}
