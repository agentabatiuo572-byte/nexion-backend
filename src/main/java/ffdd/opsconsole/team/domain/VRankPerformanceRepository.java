package ffdd.opsconsole.team.domain;

/**
 * F1 V-Rank 业绩聚合仓储(业绩聚合层接口)。
 *
 * <p>接口屏蔽 MyBatis 实现,便于 {@code VRankPromotionEngine} 单测用 fake 替换。
 * 默认口径见 {@link VRankEvaluationSnapshot}。
 */
public interface VRankPerformanceRepository {

    /**
     * 聚合用户业绩快照。
     *
     * @param userId 被评估的用户 ID
     * @return 不可变快照;无任何数据时返回全零快照(不返回 null)
     */
    VRankEvaluationSnapshot computeSnapshot(Long userId);
}
