package ffdd.opsconsole.growth.dto;

public record ReferralRewardParamUpdateRequest(
        String key,
        String value,
        Long expectedVersion,
        String reason,
        String operator) {
}
