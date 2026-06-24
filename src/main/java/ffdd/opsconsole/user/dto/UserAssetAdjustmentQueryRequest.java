package ffdd.opsconsole.user.dto;

public record UserAssetAdjustmentQueryRequest(
        String status,
        String asset,
        Long userId,
        String keyword,
        Integer pageNum,
        Integer pageSize,
        Boolean historyOnly) {
}
