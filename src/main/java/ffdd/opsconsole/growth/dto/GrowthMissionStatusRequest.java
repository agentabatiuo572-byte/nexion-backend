package ffdd.opsconsole.growth.dto;

/** H3 existing task lifecycle request with optimistic expected state. */
public record GrowthMissionStatusRequest(
        String taskKind,
        String targetStatus,
        String expectedStatus,
        String reason,
        String operator) {
}
