package ffdd.opsconsole.team.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface TeamCommissionRepository {
    List<Map<String, Object>> binarySettlements(int limit);

    Map<String, Object> binarySettlementSummary();

    List<Map<String, Object>> vRankRows();

    boolean updateVRankThreshold(String rank, String field, Object value);

    List<Map<String, Object>> f2Metrics();

    List<Map<String, Object>> unilevelRates();

    boolean updateUnilevelRule(int layerNo, String field, Object value);

    List<Map<String, Object>> rateTiers();

    List<Map<String, Object>> vRankRewards(String rank);

    boolean addVRankReward(String rank, Map<String, Object> reward);

    boolean updateVRankReward(String rank, String rewardId, Map<String, Object> reward);

    boolean deleteVRankReward(String rank, String rewardId);

    Map<String, Object> leadershipPoolSummary();

    List<Map<String, Object>> leadershipRanks();

    List<Map<String, Object>> quotaRows();

    List<Map<String, Object>> ambassadorBands();

    Map<String, Object> ambassadorSummary();

    List<Map<String, Object>> leaderboardPodium(int limit);

    Map<String, Object> leaderboardSummary();

    List<Map<String, Object>> commissionEvents(int limit);

    List<Map<String, Object>> commissionKindSummary();

    List<Map<String, Object>> commissionAuditFeed(int limit);

    boolean updateCommissionStatus(String eventId, String status);

    // F4 · 修复2:V-Rank 票权权重(leadership_votes)写业务表 nx_v_rank_config。
    // F.pool.votes.V{n} → UPDATE nx_v_rank_config.leadership_votes WHERE rank_code=V{n}。
    boolean updateVRankLeadershipVotes(String rankCode, int votes);

    // F4 · 修复3:大使审批写业务表 nx_team_ambassador_application.status。
    // applicationId 可解析为数字时按 id 精确匹配;否则 fallback 到"最新一条 PENDING"处置。
    boolean updateAmbassadorStatus(String applicationId, String status, String reviewer, String reason);

    // F4 · 修复4:榜单处置 INSERT 流水到 nx_team_leaderboard_action。
    // member_user_id=0 表示"本期全局期处置"(非针对具体用户的期级 action)。
    boolean insertLeaderboardAction(String period, String actionType, String reason, String operator);

    // ============================================================
    // F1 V-Rank 晋升引擎(Sprint 1+2):引擎读取 + 写入接口
    // ============================================================

    /** 读 13 阶 nx_v_rank_config(按 sort_order 升序),供引擎逐阶判定。 */
    List<VRankConfigRow> vRankConfigRows();

    /**
     * 读用户当前 v_rank — 来自 nx_team_member 自循环行(user_id=member_user_id=userId)。
     * 行不存在时返回 "V0"(默认阶)。
     */
    String currentMemberVRank(Long userId);

    /**
     * 更新用户 v_rank(自循环行 user_id=member_user_id=userId)。
     * 若自循环行不存在则插入一条(WHERE 条件命中 0 行时返回 false,引擎上层负责保证自循环行存在或先 INSERT)。
     */
    boolean updateMemberVRank(Long userId, String newRank);

    /**
     * INSERT nx_user_level_log 晋升流水(level_type='VRANK')。
     *
     * @param userId          被晋升用户
     * @param fromCode        原阶(如 "V0")
     * @param toCode          新阶(如 "V2")
     * @param reason          审计原因(triggerType.code())
     * @param operator        操作来源(ENGINE / admin 账号)
     * @param snapshotJson    评估快照 JSON(可 null)
     * @param triggerEventId  触发事件 ID(可 null,幂等链)
     * @param auditNo         审计序号(单次晋升唯一)
     * @param isManual        1=运营手动,0=引擎自动
     */
    boolean insertUserLevelLog(Long userId,
                               String fromCode,
                               String toCode,
                               String reason,
                               String operator,
                               String snapshotJson,
                               String triggerEventId,
                               String auditNo,
                               boolean isManual);

    // ============================================================
    // F1 V-Rank 奖励派发(Sprint 3):规则读 + commission_event/payout 写 + sponsor 查询
    // ============================================================

    /**
     * 读指定阶的全部奖励规则(is_deleted=0 AND status=1,按 sort_order 升序)。
     * 由 {@code VRankRewardDispatcher} 在晋升达成后消费。
     */
    List<VRankRewardRuleRow> selectVRankRewardRulesByRank(String rankCode);

    /**
     * 查 L1 上线 sponsor_user_id(来自 nx_sponsorship.user_id=userId)。
     * 无绑定/已软删返回 null(培育类 NEX 派发时若 null → fallback 派本人)。
     */
    Long findSponsorUserId(Long userId);

    /**
     * 幂等检查:同 user/rank/rewardType 是否已派发过(GRANTED/PENDING_GRANT/REISSUED 均算)。
     * 派发前调用,防止多次评估触发重复派发。
     */
    boolean existsVRankRewardPayout(Long userId, String rankCode, String rewardType);

    /**
     * INSERT nx_commission_event — 资金类奖励(usdt/培育 nex)落 D4 台账事件。
     *
     * @param userId          接收方 user_id(培育类=sponsor,本人奖励=被晋升人)
     * @param commissionType  {@code vrank_reward}(本人奖励) / {@code cultivation}(培育类给 sponsor)
     * @param sourceUserId    触发用户(被晋升人);cultivation 时为下线
     * @param currency        USDT / NEX
     * @param amountUsdt      USDT 金额(NEX 类为 0)
     * @param amountNex       NEX 金额(USDT 类为 0)
     * @param status          默认 PENDING(冷却) — 由后续结算流程 UNLOCKED
     * @param coolingDays     冷却天数(unlock_at = NOW()+coolingDays;PRD line231 默认30,读 commission/cooling-days 配置)
     * @param remark          审计备注(含 rank_code/operator)
     * @return 自增 id(用于 payout 关联)
     */
    Long insertCommissionEvent(Long userId,
                               String commissionType,
                               Long sourceUserId,
                               String currency,
                               java.math.BigDecimal amountUsdt,
                               java.math.BigDecimal amountNex,
                               String status,
                               int coolingDays,
                               String remark);

    /**
     * F2: INSERT nx_commission_event (network kind,带 layer_no + order_no + order_amount_usd)。
     * @param coolingDays 冷却天数(unlock_at = NOW()+coolingDays;PRD line231 默认30,读 commission/cooling-days 配置)
     * @return 自增 id(用于 D4 台账关联);0 行返回 null
     */
    Long insertNetworkCommissionEvent(Long userId,
                                      String commissionType,
                                      Long sourceUserId,
                                      Integer layerNo,
                                      String orderNo,
                                      java.math.BigDecimal orderAmountUsdt,
                                      String currency,
                                      java.math.BigDecimal amountUsdt,
                                      java.math.BigDecimal amountNex,
                                      String status,
                                      int coolingDays,
                                      String remark);

    /** F2 幂等:同 orderNo + userId + network 已派发计数。 */
    int countNetworkCommissionByOrder(Long userId, String orderNo);

    /**
     * INSERT nx_v_rank_reward_payout — 派发流水落库(所有类型最终都写一条)。
     *
     * @param payout 完整流水字段(资金类带 commission_event_id/bill_id,H7 voucher 已授权为 GRANTED,
     *               E 域 SKU 未接入时为 PENDING_GRANT)
     * @return true=插入成功
     */
    boolean insertVRankRewardPayout(VRankRewardPayout payout);

    // ============================================================
    // F1 V-Rank 晋升流水查询(Sprint 5):promotion-log 端点读取
    // ============================================================

    /**
     * 查 V-Rank 晋升流水(nx_user_level_log WHERE level_type='VRANK' AND is_deleted=0)。
     *
     * <p>LEFT JOIN nx_user 取 nickname;cohort 取 nx_janus_device.cohort_id(最新一条);
     * reason LIKE '[MANUAL]%' 标记为手动覆盖。ORDER BY created_at DESC, id DESC LIMIT 100。
     *
     * @param userId 精确匹配 user_id(null=不过滤)
     * @param v      阶代码(如 "V3",同时匹配 from_code/to_code;null/空=不过滤)
     * @param cohort nx_janus_device.cohort_id 模糊匹配(null/空=不过滤)
     * @param from   created_at >= from(YYYY-MM-DD;null/空=不过滤)
     * @param to     created_at <= to(YYYY-MM-DD 闭区间,内部拼 ' 23:59:59';null/空=不过滤)
     * @return 流水行列表(id/userId/fromCode/toCode/reason/operator/isManual/cohort/nickname/createdAt)
     */
    List<Map<String, Object>> queryPromotionLog(Long userId,
                                                String v,
                                                String cohort,
                                                String from,
                                                String to);

    // ============================================================
    // F1 V-Rank 派发流水查询/补发/撤销(Sprint 6 端点第二组)
    // ============================================================

    /**
     * 查 nx_v_rank_reward_payout 派发流水(is_deleted=0,ORDER BY granted_at DESC LIMIT 100)。
     *
     * @param type   reward_type 精确忽略大小写(null=不过滤)
     * @param v      rank_code 精确匹配(null/空=不过滤)
     * @param status status 忽略大小写(null/空=不过滤)
     * @param userId user_id 精确匹配(null=不过滤)
     * @param cursor granted_at 上界游标(null=首页)
     */
    List<Map<String, Object>> queryRewardPayouts(String type,
                                                 String v,
                                                 String status,
                                                 Long userId,
                                                 String cursor);

    /**
     * 按 payout_id 精确查单条 payout(忽略软删)。
     * 用于 reissue/reverse 端点定位原流水。
     */
    Map<String, Object> findRewardPayoutByPayoutId(String payoutId);

    /**
     * UPDATE payout 状态 + operator + reason + 时间戳(REVERSED 时同步写 reversed_at)。
     */
    boolean updateRewardPayoutStatus(String payoutId, String newStatus, String operator, String reason);

    /**
     * 红冲反向:UPDATE nx_commission_event.status='REVERSED' WHERE id=commissionEventId。
     * 用于 reverse 端点的 D4 反向冲正(OpsTeamService.postCommissionLedgerIfStatusChanged 范式)。
     *
     * @return 影响行数(0=事件不存在或已软删,调用方按需处理)
     */
    int reverseCommissionEvent(Long commissionEventId);
}
