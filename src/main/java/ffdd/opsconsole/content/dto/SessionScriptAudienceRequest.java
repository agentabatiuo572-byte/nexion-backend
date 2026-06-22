package ffdd.opsconsole.content.dto;

public record SessionScriptAudienceRequest(
        String audience,
        String operator,
        String reason) {
}
