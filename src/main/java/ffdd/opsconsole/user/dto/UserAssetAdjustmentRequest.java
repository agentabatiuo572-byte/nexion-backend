package ffdd.opsconsole.user.dto;

public record UserAssetAdjustmentRequest(
        String asset,
        String direction,
        String amount,
        String reason,
        String operator) {
}
