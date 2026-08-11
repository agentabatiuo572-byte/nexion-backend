package ffdd.opsconsole.growth.dto;

/** H3 existing task rename request. taskKind is MISSION or MONTHLY. */
public record GrowthMissionEditRequest(
        String taskKind,
        String name,
        String expectedName,
        String reason,
        String operator) {
}
