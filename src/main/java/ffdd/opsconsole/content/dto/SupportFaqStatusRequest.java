package ffdd.opsconsole.content.dto;

public record SupportFaqStatusRequest(
        String status,
        String operator,
        String reason) {
}
