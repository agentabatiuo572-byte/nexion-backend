package ffdd.opsconsole.user.dto;

public record UserAccountListUpsertRequest(
        Long userId,
        String kind,
        String reason,
        String operator,
        String expiresAt) {
}
