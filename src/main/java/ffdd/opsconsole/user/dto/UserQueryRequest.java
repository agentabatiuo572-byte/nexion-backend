package ffdd.opsconsole.user.dto;

public record UserQueryRequest(
        String keyword,
        String status,
        String kycStatus,
        Integer riskMin,
        Integer pageNum,
        Integer pageSize,
        Integer limit) {
    public UserQueryRequest(String keyword, String status, String kycStatus, Integer limit) {
        this(keyword, status, kycStatus, null, null, null, limit);
    }
}
