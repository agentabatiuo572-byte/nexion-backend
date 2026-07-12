package ffdd.opsconsole.content.dto;

public record NovaSocialEventStatusRequest(
        String status,
        String operator,
        String reason) {
}
