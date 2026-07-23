package ffdd.opsconsole.content.dto;

public record SessionCategoryToggleRequest(
        Boolean enabled,
        Boolean expectedEnabled,
        String operator,
        String reason) {
    public SessionCategoryToggleRequest(Boolean enabled, String operator, String reason) {
        this(enabled, null, operator, reason);
    }
}
