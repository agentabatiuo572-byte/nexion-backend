package ffdd.opsconsole.content.dto;

public record SessionScriptStatusRequest(
        String status,
        String operator,
        String reason) {
}
