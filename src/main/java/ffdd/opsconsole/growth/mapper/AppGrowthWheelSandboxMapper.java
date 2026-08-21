package ffdd.opsconsole.growth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Physical persistence boundary for local Lucky Spin sandbox facts.
 *
 * There are deliberately no joins to wallet, release-ledger, audit, outbox,
 * or any of the canonical wheel tables in this mapper.
 */
@Mapper
public interface AppGrowthWheelSandboxMapper extends BaseMapper<Object> {
    @Select("SELECT CASE WHEN "
            + "(SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
            + "AND table_name IN ('nx_growth_wheel_sandbox_scope','nx_growth_wheel_sandbox_tier',"
            + "'nx_growth_wheel_sandbox_guard','nx_growth_wheel_sandbox_ticket',"
            + "'nx_growth_wheel_sandbox_spin','nx_growth_wheel_sandbox_reward_ledger',"
            + "'nx_growth_wheel_sandbox_command')) = 7 "
            + "AND (SELECT COUNT(*) FROM information_schema.statistics "
            + "WHERE table_schema=DATABASE() AND seq_in_index=1 AND ((table_name='nx_growth_wheel_sandbox_tier' "
            + "AND index_name='idx_growth_wheel_sandbox_tier_scope') OR (table_name='nx_growth_wheel_sandbox_guard' "
            + "AND index_name='idx_growth_wheel_sandbox_guard_scope') OR (table_name='nx_growth_wheel_sandbox_ticket' "
            + "AND index_name='idx_growth_wheel_sandbox_ticket_scope') OR (table_name='nx_growth_wheel_sandbox_spin' "
            + "AND index_name='idx_growth_wheel_sandbox_spin_scope') OR (table_name='nx_growth_wheel_sandbox_reward_ledger' "
            + "AND index_name='idx_growth_wheel_sandbox_reward_scope') OR (table_name='nx_growth_wheel_sandbox_command' "
            + "AND index_name='idx_growth_wheel_sandbox_command_scope'))) = 6 "
            + "AND (SELECT COUNT(*) FROM information_schema.check_constraints cc "
            + "JOIN information_schema.table_constraints tc ON tc.constraint_schema=cc.constraint_schema "
            + "AND tc.constraint_name=cc.constraint_name AND tc.constraint_type='CHECK' "
            + "WHERE cc.constraint_schema=DATABASE() AND ((tc.table_name='nx_growth_wheel_sandbox_scope' "
            + "AND cc.constraint_name='chk_growth_wheel_sandbox_scope_source') OR (tc.table_name='nx_growth_wheel_sandbox_tier' "
            + "AND cc.constraint_name='chk_growth_wheel_sandbox_tier_source') OR (tc.table_name='nx_growth_wheel_sandbox_guard' "
            + "AND cc.constraint_name='chk_growth_wheel_sandbox_guard_source') OR (tc.table_name='nx_growth_wheel_sandbox_ticket' "
            + "AND cc.constraint_name IN ('chk_growth_wheel_sandbox_ticket_source','chk_growth_wheel_sandbox_ticket_kind')) "
            + "OR (tc.table_name='nx_growth_wheel_sandbox_spin' AND cc.constraint_name='chk_growth_wheel_sandbox_spin_source') "
            + "OR (tc.table_name='nx_growth_wheel_sandbox_reward_ledger' AND cc.constraint_name='chk_growth_wheel_sandbox_reward_source') "
            + "OR (tc.table_name='nx_growth_wheel_sandbox_command' AND cc.constraint_name='chk_growth_wheel_sandbox_command_source'))) = 8 "
            + "THEN 7 ELSE 0 END")
    int sandboxSchemaTableCount();

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 "
            + "AND COALESCE(sandbox,0)=1 LIMIT 1")
    Long findSandboxUser(@Param("userId") Long userId);

    @Insert("INSERT IGNORE INTO nx_growth_wheel_sandbox_scope(run_id,user_id,source,source_environment,created_at,updated_at) "
            + "VALUES(#{runId},#{userId},'mock','SANDBOX',NOW(),NOW())")
    int ensureScope(@Param("runId") String runId, @Param("userId") Long userId);

    @Select("SELECT id FROM nx_growth_wheel_sandbox_scope WHERE run_id=#{runId} AND user_id=#{userId} "
            + "AND source='mock' AND source_environment='SANDBOX' FOR UPDATE")
    Long lockScope(@Param("runId") String runId, @Param("userId") Long userId);

    @Insert("INSERT IGNORE INTO nx_growth_wheel_sandbox_tier "
            + "(run_id,user_id,tier_name,reward_name,probability_pct,reward_kind,reward_amount,real_outflow,daily_stock,sort_order,status,is_deleted,source,source_environment,created_at,updated_at) "
            + "VALUES(#{runId},#{userId},#{tierName},#{rewardName},#{probabilityPct},#{rewardKind},#{rewardAmount},0,0,#{sortOrder},1,0,'mock','SANDBOX',NOW(),NOW())")
    int ensureTier(@Param("runId") String runId, @Param("userId") Long userId,
                   @Param("tierName") String tierName, @Param("rewardName") String rewardName,
                   @Param("probabilityPct") BigDecimal probabilityPct, @Param("rewardKind") String rewardKind,
                   @Param("rewardAmount") BigDecimal rewardAmount, @Param("sortOrder") int sortOrder);

    @Insert("INSERT IGNORE INTO nx_growth_wheel_sandbox_guard "
            + "(run_id,user_id,guard_key,guard_value,status,is_deleted,source,source_environment,created_at,updated_at) "
            + "VALUES(#{runId},#{userId},#{guardKey},#{guardValue},1,0,'mock','SANDBOX',NOW(),NOW())")
    int ensureGuard(@Param("runId") String runId, @Param("userId") Long userId,
                    @Param("guardKey") String guardKey, @Param("guardValue") String guardValue);

    @Select("SELECT tier_id tierId,tier_name tierName,reward_name rewardName,probability_pct probabilityPct,"
            + "reward_kind rewardKind,reward_amount rewardAmount,real_outflow realOutflow,daily_stock dailyStock "
            + "FROM nx_growth_wheel_sandbox_tier WHERE run_id=#{runId} AND user_id=#{userId} "
            + "AND status=1 AND is_deleted=0 AND source='mock' AND source_environment='SANDBOX' ORDER BY sort_order,id")
    List<SandboxTier> listTiers(@Param("runId") String runId, @Param("userId") Long userId);

    @Select("SELECT tier_id tierId,tier_name tierName,reward_name rewardName,probability_pct probabilityPct,"
            + "reward_kind rewardKind,reward_amount rewardAmount,real_outflow realOutflow,daily_stock dailyStock "
            + "FROM nx_growth_wheel_sandbox_tier WHERE run_id=#{runId} AND user_id=#{userId} "
            + "AND status=1 AND is_deleted=0 AND source='mock' AND source_environment='SANDBOX' ORDER BY sort_order,id FOR UPDATE")
    List<SandboxTier> lockTiers(@Param("runId") String runId, @Param("userId") Long userId);

    @Select("SELECT guard_value FROM nx_growth_wheel_sandbox_guard WHERE run_id=#{runId} AND user_id=#{userId} "
            + "AND guard_key=#{guardKey} AND status=1 AND is_deleted=0 AND source='mock' AND source_environment='SANDBOX' LIMIT 1 FOR UPDATE")
    String lockGuardValue(@Param("runId") String runId, @Param("userId") Long userId,
                          @Param("guardKey") String guardKey);

    @Select("SELECT COUNT(*) FROM nx_growth_wheel_sandbox_spin WHERE run_id=#{runId} AND user_id=#{userId} "
            + "AND event_code=#{eventCode} AND spin_date=#{spinDate} AND source_type='DAILY' AND is_deleted=0 "
            + "AND source='mock' AND source_environment='SANDBOX'")
    int countDailySpin(@Param("runId") String runId, @Param("userId") Long userId,
                       @Param("eventCode") String eventCode, @Param("spinDate") LocalDate spinDate);

    @Select("SELECT COUNT(*) FROM nx_growth_wheel_sandbox_ticket WHERE run_id=#{runId} AND user_id=#{userId} "
            + "AND status='AVAILABLE' AND is_deleted=0 AND source='mock' AND source_environment='SANDBOX'")
    int countAvailableTickets(@Param("runId") String runId, @Param("userId") Long userId);

    @Select("SELECT ticket_id ticketId FROM nx_growth_wheel_sandbox_ticket WHERE run_id=#{runId} AND user_id=#{userId} "
            + "AND status='AVAILABLE' AND is_deleted=0 AND source='mock' AND source_environment='SANDBOX' ORDER BY created_at,id LIMIT 1 FOR UPDATE")
    SandboxTicket lockAvailableTicket(@Param("runId") String runId, @Param("userId") Long userId);

    @Update("UPDATE nx_growth_wheel_sandbox_ticket SET status='USED',used_event_code=#{eventCode},spin_date=#{spinDate},"
            + "used_at=NOW(),updated_at=NOW() WHERE run_id=#{runId} AND user_id=#{userId} AND ticket_id=#{ticketId} "
            + "AND status='AVAILABLE' AND is_deleted=0 AND source='mock' AND source_environment='SANDBOX'")
    int consumeTicket(@Param("runId") String runId, @Param("userId") Long userId,
                      @Param("ticketId") String ticketId, @Param("eventCode") String eventCode,
                      @Param("spinDate") LocalDate spinDate);

    @Insert("INSERT IGNORE INTO nx_growth_wheel_sandbox_ticket "
            + "(run_id,user_id,ticket_id,source_type,source_id,status,source,source_environment,created_at,updated_at,is_deleted) "
            + "VALUES(#{runId},#{userId},#{ticketId},#{sourceType},#{sourceId},'AVAILABLE','mock','SANDBOX',NOW(),NOW(),0)")
    int insertTicket(@Param("runId") String runId, @Param("userId") Long userId,
                     @Param("ticketId") String ticketId, @Param("sourceType") String sourceType,
                     @Param("sourceId") String sourceId);

    @Select("SELECT COUNT(*) FROM nx_growth_wheel_sandbox_ticket WHERE run_id=#{runId} AND user_id=#{userId} "
            + "AND source_type=#{sourceType} AND source_id LIKE CONCAT(#{sourceIdPrefix},'%') AND is_deleted=0 "
            + "AND source='mock' AND source_environment='SANDBOX'")
    int countTicketsBySource(@Param("runId") String runId, @Param("userId") Long userId,
                             @Param("sourceType") String sourceType, @Param("sourceIdPrefix") String sourceIdPrefix);

    @Select("SELECT spin_no spinId,spin_date spinDate,source_type sourceType,source_id sourceId,tier_id tierId,"
            + "tier_name tierName,reward_kind rewardKind,reward_amount rewardAmount,reward_name rewardName,"
            + "downgraded,downgrade_reason downgradeReason,created_at awardedAt "
            + "FROM nx_growth_wheel_sandbox_spin WHERE run_id=#{runId} AND user_id=#{userId} "
            + "AND event_code=#{eventCode} AND is_deleted=0 AND source='mock' AND source_environment='SANDBOX' "
            + "ORDER BY created_at DESC,id DESC LIMIT #{limit}")
    List<Map<String, Object>> listHistory(@Param("runId") String runId, @Param("userId") Long userId,
                                          @Param("eventCode") String eventCode, @Param("limit") int limit);

    @Select("SELECT spin_no spinId,spin_date spinDate,source_type sourceType,source_id sourceId,tier_id tierId,"
            + "tier_name tierName,reward_kind rewardKind,reward_amount rewardAmount,reward_name rewardName,"
            + "downgraded,downgrade_reason downgradeReason,created_at awardedAt "
            + "FROM nx_growth_wheel_sandbox_spin WHERE run_id=#{runId} AND user_id=#{userId} "
            + "AND spin_no=#{spinNo} AND is_deleted=0 AND source='mock' AND source_environment='SANDBOX' LIMIT 1")
    SandboxSpin findSpin(@Param("runId") String runId, @Param("userId") Long userId,
                         @Param("spinNo") String spinNo);

    @Insert("INSERT IGNORE INTO nx_growth_wheel_sandbox_spin "
            + "(run_id,user_id,spin_no,event_code,spin_date,source_type,source_id,tier_id,tier_name,reward_name,"
            + "reward_kind,reward_amount,real_outflow,downgraded,downgrade_reason,source,source_environment,created_at,updated_at,is_deleted) "
            + "VALUES(#{runId},#{userId},#{spinNo},#{eventCode},#{spinDate},#{sourceType},#{sourceId},#{tier.tierId},"
            + "#{tier.tierName},#{tier.rewardName},#{tier.rewardKind},#{tier.rewardAmount},0,#{downgraded},#{downgradeReason},"
            + "'mock','SANDBOX',NOW(),NOW(),0)")
    int insertSpin(@Param("runId") String runId, @Param("userId") Long userId,
                   @Param("spinNo") String spinNo, @Param("eventCode") String eventCode,
                   @Param("spinDate") LocalDate spinDate, @Param("sourceType") String sourceType,
                   @Param("sourceId") String sourceId, @Param("tier") SandboxTier tier,
                   @Param("downgraded") boolean downgraded, @Param("downgradeReason") String downgradeReason);

    @Select("SELECT COALESCE(SUM(amount),0) FROM nx_growth_wheel_sandbox_reward_ledger "
            + "WHERE run_id=#{runId} AND user_id=#{userId} AND asset=#{asset} AND is_deleted=0 "
            + "AND source='mock' AND source_environment='SANDBOX'")
    BigDecimal rewardBalance(@Param("runId") String runId, @Param("userId") Long userId,
                             @Param("asset") String asset);

    @Insert("INSERT INTO nx_growth_wheel_sandbox_reward_ledger "
            + "(run_id,user_id,biz_no,asset,amount,balance_after,status,source,source_environment,created_at,updated_at,is_deleted) "
            + "VALUES(#{runId},#{userId},#{bizNo},#{asset},#{amount},#{balanceAfter},'POSTED','mock','SANDBOX',NOW(),NOW(),0)")
    int insertReward(@Param("runId") String runId, @Param("userId") Long userId,
                     @Param("bizNo") String bizNo, @Param("asset") String asset,
                     @Param("amount") BigDecimal amount, @Param("balanceAfter") BigDecimal balanceAfter);

    @Select("SELECT operation,request_hash spinHash,spin_no spinNo FROM nx_growth_wheel_sandbox_command "
            + "WHERE run_id=#{runId} AND user_id=#{userId} AND operation=#{operation} AND idempotency_key=#{key} "
            + "AND is_deleted=0 AND source='mock' AND source_environment='SANDBOX' LIMIT 1 FOR UPDATE")
    SandboxCommand lockCommand(@Param("runId") String runId, @Param("userId") Long userId,
                               @Param("operation") String operation, @Param("key") String key);

    @Insert("INSERT IGNORE INTO nx_growth_wheel_sandbox_command "
            + "(run_id,user_id,operation,idempotency_key,request_hash,spin_no,source,source_environment,created_at,updated_at,is_deleted) "
            + "VALUES(#{runId},#{userId},#{operation},#{key},#{requestHash},#{spinNo},'mock','SANDBOX',NOW(),NOW(),0)")
    int insertCommand(@Param("runId") String runId, @Param("userId") Long userId,
                      @Param("operation") String operation, @Param("key") String key,
                      @Param("requestHash") String requestHash, @Param("spinNo") String spinNo);

    @Select("SELECT CASE WHEN EXISTS(SELECT 1 FROM information_schema.tables "
            + "WHERE table_schema=DATABASE() AND table_name='nx_growth_quest_sandbox') "
            + "AND EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() "
            + "AND table_name='nx_growth_quest_sandbox' AND column_name='claim_idempotency_key') "
            + "AND EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() "
            + "AND table_name='nx_growth_quest_sandbox' AND index_name='uk_growth_quest_sandbox_scope') "
            + "AND (SELECT COUNT(*) FROM information_schema.check_constraints cc "
            + "JOIN information_schema.table_constraints tc ON tc.constraint_schema=cc.constraint_schema "
            + "AND tc.constraint_name=cc.constraint_name AND tc.constraint_type='CHECK' "
            + "WHERE cc.constraint_schema=DATABASE() AND tc.table_name='nx_growth_quest_sandbox' "
            + "AND tc.constraint_name IN ('chk_growth_quest_sandbox_source','chk_growth_quest_sandbox_status'))=2 "
            + "THEN 1 ELSE 0 END")
    int questSandboxSchemaTableCount();

    @Insert("INSERT IGNORE INTO nx_growth_quest_sandbox "
            + "(run_id,user_id,quest_code,quest_name,layer,reward_nex,mission_status,source,source_environment,"
            + "created_at,updated_at,is_deleted) VALUES(#{runId},#{userId},#{questCode},#{questName},#{layer},"
            + "#{rewardNex},'PENDING','mock','SANDBOX',NOW(),NOW(),0)")
    int ensureQuest(@Param("runId") String runId, @Param("userId") Long userId,
                    @Param("questCode") String questCode, @Param("questName") String questName,
                    @Param("layer") String layer, @Param("rewardNex") BigDecimal rewardNex);

    @Select("""
            SELECT COUNT(*)
              FROM nx_growth_quest_sandbox q
              JOIN nx_mission m
                ON LOWER(m.mission_code)=LOWER(q.quest_code)
               AND m.mission_type IN ('WEEKLY_T1','WEEKLY_T2')
               AND m.status=1 AND m.is_deleted=0
             WHERE q.run_id=#{runId} AND q.user_id=#{userId}
               AND q.source='mock' AND q.source_environment='SANDBOX'
               AND LOWER(q.quest_code) IN
                   ('bind_bank_card','visit_earn','visit_store','view_product_roi','setup_profile','invite_friend')
            """)
    int countActiveWeeklyCodeCollisions(@Param("runId") String runId, @Param("userId") Long userId);

    /**
     * Mirrors only the active PC-managed weekly definitions into the current
     * sandbox run. User progress remains exclusively in nx_growth_quest_sandbox.
     */
    @Insert("""
            INSERT INTO nx_growth_quest_sandbox
                   (run_id,user_id,quest_code,quest_name,layer,reward_nex,mission_status,
                    sort_order,source,source_environment,created_at,updated_at,is_deleted)
            SELECT #{runId},#{userId},m.mission_code,m.mission_name,m.mission_type,
                   CAST(m.reward_points AS DECIMAL(18,6)),'PENDING',
                   LEAST(m.id,2147483647),'mock','SANDBOX',NOW(),NOW(),0
              FROM nx_mission m
             WHERE m.mission_type IN ('WEEKLY_T1','WEEKLY_T2')
               AND m.status=1 AND m.is_deleted=0
            ON DUPLICATE KEY UPDATE
                   quest_name=VALUES(quest_name),layer=VALUES(layer),reward_nex=VALUES(reward_nex),
                   sort_order=VALUES(sort_order),is_deleted=0,updated_at=NOW()
            """)
    int syncActiveWeeklyQuests(@Param("runId") String runId, @Param("userId") Long userId);

    @Update("""
            UPDATE nx_growth_quest_sandbox q
            LEFT JOIN nx_mission m
                   ON m.mission_code=q.quest_code
                  AND m.mission_type=q.layer
                  AND m.status=1 AND m.is_deleted=0
               SET q.is_deleted=1,q.updated_at=NOW()
             WHERE q.run_id=#{runId} AND q.user_id=#{userId}
               AND q.source='mock' AND q.source_environment='SANDBOX'
               AND q.layer IN ('WEEKLY_T1','WEEKLY_T2')
               AND q.is_deleted=0 AND m.id IS NULL
            """)
    int deactivateInactiveWeeklyQuests(@Param("runId") String runId, @Param("userId") Long userId);

    @Select("SELECT quest_code questCode,quest_name questName,layer,reward_nex rewardNex,mission_status missionStatus,claim_idempotency_key claimIdempotencyKey "
            + "FROM nx_growth_quest_sandbox WHERE run_id=#{runId} AND user_id=#{userId} "
            + "AND source='mock' AND source_environment='SANDBOX' AND is_deleted=0 "
            + "ORDER BY sort_order,id")
    List<SandboxQuest> listQuests(@Param("runId") String runId, @Param("userId") Long userId);

    @Select("SELECT quest_code questCode,quest_name questName,layer,reward_nex rewardNex,mission_status missionStatus,claim_idempotency_key claimIdempotencyKey "
            + "FROM nx_growth_quest_sandbox WHERE run_id=#{runId} AND user_id=#{userId} "
            + "AND quest_code=#{questCode} AND source='mock' AND source_environment='SANDBOX' AND is_deleted=0 "
            + "LIMIT 1 FOR UPDATE")
    SandboxQuest lockQuest(@Param("runId") String runId, @Param("userId") Long userId,
                           @Param("questCode") String questCode);

    @Update("UPDATE nx_growth_quest_sandbox SET mission_status='COMPLETED',updated_at=NOW() "
            + "WHERE run_id=#{runId} AND user_id=#{userId} AND quest_code=#{questCode} "
            + "AND mission_status='PENDING' AND source='mock' AND source_environment='SANDBOX' AND is_deleted=0")
    int completeQuest(@Param("runId") String runId, @Param("userId") Long userId,
                      @Param("questCode") String questCode);

    @Update("UPDATE nx_growth_quest_sandbox SET mission_status='COMPLETED',claim_idempotency_key=#{eventId},updated_at=NOW() "
            + "WHERE run_id=#{runId} AND user_id=#{userId} AND quest_code=#{questCode} "
            + "AND mission_status='PENDING' AND source='mock' AND source_environment='SANDBOX' AND is_deleted=0")
    int completeShareQuest(@Param("runId") String runId, @Param("userId") Long userId,
                           @Param("questCode") String questCode, @Param("eventId") String eventId);

    @Update("UPDATE nx_growth_quest_sandbox SET mission_status='CLAIMED',claim_idempotency_key=COALESCE(claim_idempotency_key,#{idempotencyKey}),updated_at=NOW() "
            + "WHERE run_id=#{runId} AND user_id=#{userId} AND quest_code=#{questCode} "
            + "AND mission_status IN ('COMPLETED','CLAIMABLE') AND source='mock' AND source_environment='SANDBOX' AND is_deleted=0")
    int claimQuest(@Param("runId") String runId, @Param("userId") Long userId,
                   @Param("questCode") String questCode, @Param("idempotencyKey") String idempotencyKey);

    record SandboxTier(Long tierId, String tierName, String rewardName, BigDecimal probabilityPct,
                       String rewardKind, BigDecimal rewardAmount, Boolean realOutflow, Integer dailyStock) { }
    record SandboxTicket(String ticketId) { }
    record SandboxSpin(String spinId, LocalDate spinDate, String sourceType, String sourceId, Long tierId,
                       String tierName, String rewardKind, BigDecimal rewardAmount, String rewardName,
                       Boolean downgraded, String downgradeReason, java.time.LocalDateTime awardedAt) { }
    record SandboxCommand(String operation, String spinHash, String spinNo) { }
    record SandboxQuest(String questCode, String questName, String layer, BigDecimal rewardNex,
                        String missionStatus, String claimIdempotencyKey) { }
}
