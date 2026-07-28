package ffdd.opsconsole.growth.dto;

public record GrowthPowerUpUpdateRequest(
        Integer day,
        String note,
        Integer expectedDay,
        String expectedNote,
        String reason,
        String operator) {
}
