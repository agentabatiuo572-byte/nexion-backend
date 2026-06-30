package ffdd.opsconsole.user.dto;

public record UserAssetAdjustmentRequest(
        String asset,
        String direction,
        String amount,
        String reason,
        String operator,
        String referenceType,
        String referenceId) {

    public UserAssetAdjustmentRequest(String asset, String direction, String amount, String reason, String operator) {
        this(asset, direction, amount, reason, operator, null, null);
    }
}
