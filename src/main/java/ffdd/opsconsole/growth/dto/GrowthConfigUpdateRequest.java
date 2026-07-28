package ffdd.opsconsole.growth.dto;

public record GrowthConfigUpdateRequest(
        String key,
        String value,
        String reason,
        String operator,
        String expectedValue) {

    public GrowthConfigUpdateRequest(String key, String value, String reason, String operator) {
        this(key, value, reason, operator, null);
    }
}
