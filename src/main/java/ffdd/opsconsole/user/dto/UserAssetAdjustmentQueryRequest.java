package ffdd.opsconsole.user.dto;

public record UserAssetAdjustmentQueryRequest(
        String status,
        String asset,
        Long userId,
        String keyword,
        Integer pageNum,
        Integer pageSize,
        Boolean historyOnly) {

    public UserAssetAdjustmentQueryRequest(
            String status,
            String asset,
            Long userId,
            String keyword,
            Integer pageNum,
            Integer pageSize) {
        this(status, asset, userId, keyword, pageNum, pageSize, null);
    }
}
