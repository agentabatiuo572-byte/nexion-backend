package ffdd.opsconsole.platform.dto;

public record AdminRbacActionCreateRequest(
        String action,
        String domainGroup,
        String reason,
        String operator) {
}
