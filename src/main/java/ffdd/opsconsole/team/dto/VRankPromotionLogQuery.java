package ffdd.opsconsole.team.dto;

/**
 * F1 V-Rank 晋升流水查询条件(Sprint 5 端点 3 入参)。
 *
 * <p>全部字段可选,缺省表示不过滤。映射到 {@code nx_user_level_log} WHERE level_type='VRANK' 的筛选条件。
 *
 * <ul>
 *   <li>{@code userId} — 精确匹配 user_id。</li>
 *   <li>{@code v} — 指定阶代码(如 "V3"),同时匹配 from_code 或 to_code。</li>
 *   <li>{@code cohort} — 来自 nx_user 的 cohort 字段(LEFT JOIN,为空时不过滤)。</li>
 *   <li>{@code from} / {@code to} — created_at 时间窗(YYYY-MM-DD,闭区间)。</li>
 * </ul>
 */
public record VRankPromotionLogQuery(
        Long userId,
        String v,
        String cohort,
        String from,
        String to) {
}
