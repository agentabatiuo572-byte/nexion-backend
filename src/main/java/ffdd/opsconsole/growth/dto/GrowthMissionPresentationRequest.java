package ffdd.opsconsole.growth.dto;

/** H3 PC-owned task category and direct App completion route update with CAS. */
public record GrowthMissionPresentationRequest(
        String category,
        String actionRoute,
        String expectedCategory,
        String expectedActionRoute,
        String reason,
        String operator) {
}
