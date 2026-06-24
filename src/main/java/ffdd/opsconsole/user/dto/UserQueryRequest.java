package ffdd.opsconsole.user.dto;

public record UserQueryRequest(
        String keyword,
        String status,
        String kycStatus,
        Integer riskMin,
        Integer pageNum,
        Integer pageSize,
        Integer limit) {}
