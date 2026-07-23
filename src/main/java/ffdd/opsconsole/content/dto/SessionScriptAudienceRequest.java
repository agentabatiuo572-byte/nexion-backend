package ffdd.opsconsole.content.dto;

public record SessionScriptAudienceRequest(
        String audience,
        String expectedAudience,
        String operator,
        String reason) {
    public SessionScriptAudienceRequest(String audience, String operator, String reason) {
        this(audience, null, operator, reason);
    }
}
