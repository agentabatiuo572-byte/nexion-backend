package ffdd.opsconsole.team.domain;

import java.math.BigDecimal;

/**
 * F1 V-Rank 门槛配置(nx_v_rank_config 单行领域映射)。
 *
 * <p>晋升引擎逐阶判定时消费。字段语义对齐 schema.sql 的 nx_v_rank_config:
 * <ul>
 *   <li>{@link #rankCode} — V0..V12</li>
 *   <li>{@link #selfBuyUsd} — 自购门槛(>0 才作为条件)</li>
 *   <li>{@link #directRefs} — 直接推荐人数门槛(>0 才作为条件)</li>
 *   <li>{@link #teamVolumeUsd} — 团队 L1-L7 业绩门槛(>0 才作为条件)</li>
 *   <li>{@link #requiredDownlineRank} — 下线要求的 V 阶代码(如 "V2"),配合 {@link #requiredDownlineCount}</li>
 *   <li>{@link #requiredDownlineCount} — 下线达到 {@link #requiredDownlineRank} 阶的最少条数</li>
 *   <li>{@link #sortOrder} — 阶序(V0=0..V12=12)</li>
 *   <li>{@link #unilevelDepth} — 层级深度描述(如 "L1-L7",字符串;Sprint6 扩展,联动预览读)</li>
 *   <li>{@link #peerBonusRate} — 平级奖励比例(0-1 之间小数;Sprint6 扩展,联动预览读)</li>
 *   <li>{@link #leadershipVotes} — 领导池票权(Sprint6 扩展,联动预览读;对齐 nx_v_rank_config.leadership_votes)</li>
 * </ul>
 *
 * <p>"字段 > 0 才作为条件"是核心约定:缺失字段(value=0 或 null)视为该阶不要求此维度,
 * 避免把"未配置"误解为"门槛=0 必须=0"。
 *
 * <p>Sprint6 字段扩展:unilevelDepth/peerBonusRate/leadershipVotes 用于 OpsTeamService.overrideVRank
 * 的 beforePreview/afterPreview 联动预览(Sprint5 留的 null 占位补齐)。
 */
public record VRankConfigRow(
        String rankCode,
        BigDecimal selfBuyUsd,
        int directRefs,
        BigDecimal teamVolumeUsd,
        String requiredDownlineRank,
        int requiredDownlineCount,
        int sortOrder,
        String unilevelDepth,
        BigDecimal peerBonusRate,
        int leadershipVotes) {
}
