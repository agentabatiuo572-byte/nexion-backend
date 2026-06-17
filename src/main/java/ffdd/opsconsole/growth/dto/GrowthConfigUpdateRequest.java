package ffdd.opsconsole.growth.dto;

public record GrowthConfigUpdateRequest(
        String key,
        String value,
        String reason,
        String operator) {
}
