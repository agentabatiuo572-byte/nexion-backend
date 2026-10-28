package ffdd.opsconsole.team.domain;

import java.util.Collections;
import java.util.Map;

/**
 * F1 V-Rank 业绩聚合快照(业绩聚合层 VO,默认口径)。
 *
 * <p>本类是 {@link VRankPerformanceRepository#computeSnapshot(Long)} 的返回值,
 * 由晋升引擎 {@code VRankPromotionEngine} 消费,用于逐阶判定是否满足晋升条件。
 *
 * <ul>
 *   <li>{@link #selfComputePoints} — 用户自己已付订单的 SUM(compute_points)(下单时快照的算力点),
 *       口径:payment_status IN ('PAID','CONFIRMED','SUCCESS')
 *       AND order_status NOT IN ('REFUNDED','CHARGEBACK')。</li>
 *   <li>{@link #teamComputePoints} — unilevel L1-L7 子树所有成员自购算力点之和(递归 CTE,限深 7),
 *       不含用户自己。</li>
 *   <li>{@link #directRefs} — L1 直接成员中自购算力点 ≥ V1 门槛的 COUNT DISTINCT。</li>
 *   <li>{@link #legCounts} — L1 直接成员按 v_rank 分组计数,key=V 阶数字(V0=0,V1=1,...,V8=8),
 *       value=该阶及以上视为符合 required_downline_rank 的成员数;
 *       引擎消费时按 targetV 累加 ≥ targetV 的成员数。</li>
 * </ul>
 */
public record VRankEvaluationSnapshot(
        long selfComputePoints,
        long teamComputePoints,
        int directRefs,
        Map<Integer, Integer> legCounts) {

    public VRankEvaluationSnapshot {
        if (legCounts == null) {
            legCounts = Collections.emptyMap();
        } else {
            legCounts = Collections.unmodifiableMap(legCounts);
        }
    }

    /** 便捷工厂:全零空快照(用户/订单数据缺失时使用)。 */
    public static VRankEvaluationSnapshot empty() {
        return new VRankEvaluationSnapshot(0L, 0L, 0, Collections.emptyMap());
    }
}
