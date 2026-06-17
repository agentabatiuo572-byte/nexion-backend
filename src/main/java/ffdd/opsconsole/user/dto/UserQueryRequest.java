package ffdd.opsconsole.user.dto;

public record UserQueryRequest(
        String keyword,
        String status,
        String kycStatus,
        Integer limit) {
}
