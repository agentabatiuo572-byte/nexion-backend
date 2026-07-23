package ffdd.opsconsole.user.dto;

public record UserAssetAdjustmentRequest(
        String asset,
        String direction,
        String amount,
        String reasonCode,
        String reason,
        String evidenceRef,
        String reversalOf,
        String operator,
        String referenceType,
        String referenceId) {

    public UserAssetAdjustmentRequest(String asset, String direction, String amount, String reason, String operator) {
        this(asset, direction, amount, "OPS_USER_ADJUSTMENT", reason, "legacy:manual-command", null, operator, null, null);
    }

    public UserAssetAdjustmentRequest(
            String asset,
            String direction,
            String amount,
            String reason,
            String operator,
            String referenceType,
            String referenceId) {
        this(asset, direction, amount, "OPS_USER_ADJUSTMENT", reason,
                referenceId == null ? "legacy:manual-command" : "reference:" + referenceId,
                null, operator, referenceType, referenceId);
    }
}
