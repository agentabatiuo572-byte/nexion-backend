package ffdd.opsconsole.content.dto;

public record SessionCategoryToggleRequest(
        Boolean enabled,
        String operator,
        String reason) {
}
