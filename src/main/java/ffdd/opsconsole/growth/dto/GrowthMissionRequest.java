package ffdd.opsconsole.growth.dto;

/**
 * H3 任务(dayOne / weeklyT1 / weeklyT2)新建请求。
 * missionType 白名单:DAY_ONE / WEEKLY_T1 / WEEKLY_T2(monthlyMissions 走独立端点)。
 */
public record GrowthMissionRequest(
        String missionCode,
        String missionName,
        String missionType,
        Integer rewardPoints,
        String category,
        String actionRoute,
        String reason,
        String operator) {

    /** Compatibility constructor for internal callers that predate PC-owned presentation fields. */
    public GrowthMissionRequest(
            String missionCode, String missionName, String missionType, Integer rewardPoints,
            String reason, String operator) {
        this(missionCode, missionName, missionType, rewardPoints,
                "EXPLORE", "/pages/missions/missions", reason, operator);
    }
}
