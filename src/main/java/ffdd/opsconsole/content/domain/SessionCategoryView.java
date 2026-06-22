package ffdd.opsconsole.content.domain;

public record SessionCategoryView(
        String type,
        String name,
        String roleKey,
        boolean enabled,
        String managedBy,
        boolean readOnly) {
}
