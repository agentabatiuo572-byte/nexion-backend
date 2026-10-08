package ffdd.opsconsole.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AppTeamInsightsMapper extends BaseMapper<Object> {
    // Groups are authoritative when present, including before either currency event exists.
    // Unlinked historical events keep their own IDs: an order number alone is not a group key.
    String PURCHASE_REWARDS = """
        WITH reward_sources AS (
          SELECT g.settlement_no rewardId,g.source_user_id sourceUserId,
                 COALESCE(NULLIF(TRIM(g.source_user_name),''),source.nickname) sourceUserName,
                 g.layer_no layerNo,g.source_ref orderNo,DATE_FORMAT(g.created_at,'%x-W%v') cycle,
                 o.subtotal_usdt orderAmountUsd,g.amount_usdt amountUsdt,g.amount_nex amountNex,
                 'DUAL' currency,g.status,g.created_at createdAt,g.release_at unlockAt,
                 g.source_type kind,g.source_ref sourceRef,g.policy_version policyVersion,
                 g.nex_usdt_price nexUsdtPrice,g.recovery_pending_usdt recoveryPendingUsdt,
                 g.recovery_pending_nex recoveryPendingNex,
                 g.cancelled_usdt cancelledUsdt,g.cancelled_nex cancelledNex,
                 COALESCE(g.credited_usdt_at,g.credited_at) creditedUsdtAt,
                 COALESCE(g.credited_nex_at,g.credited_at) creditedNexAt,
                 COALESCE(usdt_event.status,g.status) statusUsdt,COALESCE(nex_event.status,g.status) statusNex
            FROM nx_direct_referral_settlement g JOIN nx_user u ON u.id=g.beneficiary_user_id
            LEFT JOIN nx_commission_event usdt_event ON usdt_event.id=g.usdt_event_id AND usdt_event.is_deleted=0
            LEFT JOIN nx_commission_event nex_event ON nex_event.id=g.nex_event_id AND nex_event.is_deleted=0
            LEFT JOIN nx_user source ON source.id=g.source_user_id AND source.sandbox=u.sandbox AND source.is_deleted=0
            LEFT JOIN nx_order o ON o.order_no=g.source_ref AND o.user_id=g.source_user_id AND o.is_deleted=0
           WHERE g.beneficiary_user_id=#{userId} AND u.status='ACTIVE' AND u.is_deleted=0 AND u.sandbox=#{sandbox}
             AND g.source_environment=#{sourceEnvironment} AND g.run_id=#{runId}
             AND g.source_type IN ('network','direct_purchase') AND g.layer_no BETWEEN 1 AND 7 AND g.created_at &lt;= #{snapshotAt}
             AND (#{fromInclusive} IS NULL OR g.created_at &gt;= #{fromInclusive})
             AND (#{toExclusive} IS NULL OR g.created_at &lt; #{toExclusive})
          UNION ALL
          SELECT CONCAT('CM-',ce.id),ce.source_user_id,
                 COALESCE(NULLIF(TRIM(ce.source_user_name),''),source.nickname),
                 ce.layer_no,ce.order_no,DATE_FORMAT(ce.created_at,'%x-W%v'),
                 ce.order_amount_usd,ce.amount_usdt,ce.amount_nex,ce.currency,ce.status,ce.created_at,ce.unlock_at,
                 LOWER(ce.commission_type),ce.order_no,NULL,NULL,0,0,0,0,NULL,NULL,ce.status,ce.status
            FROM nx_commission_event ce JOIN nx_user u ON u.id=ce.user_id
            LEFT JOIN nx_user source ON source.id=ce.source_user_id AND source.sandbox=u.sandbox AND source.is_deleted=0
           WHERE ce.user_id=#{userId} AND ce.is_deleted=0 AND u.status='ACTIVE' AND u.is_deleted=0 AND u.sandbox=#{sandbox}
             AND #{sourceEnvironment}='PRODUCTION' AND ce.layer_no BETWEEN 1 AND 7
             AND ce.created_at &lt;= #{snapshotAt} AND LOWER(ce.commission_type) IN ('network','unilevel','direct','direct_purchase')
             AND (#{fromInclusive} IS NULL OR ce.created_at &gt;= #{fromInclusive})
             AND (#{toExclusive} IS NULL OR ce.created_at &lt; #{toExclusive})
             AND NOT EXISTS(SELECT 1 FROM nx_direct_referral_settlement g
                            WHERE g.usdt_event_id=ce.id OR g.nex_event_id=ce.id)
        ), rewards AS (
          SELECT reward_sources.*,
                 CASE WHEN UPPER(statusUsdt) IN ('PENDING','LOCKED','COOLING','FROZEN','WAITING_CALCULATION','UNLOCKED','AVAILABLE','SETTLED','PAID','WITHDRAWN')
                      THEN GREATEST(amountUsdt-cancelledUsdt,0) ELSE 0 END netUsdt,
                 CASE WHEN UPPER(statusNex) IN ('PENDING','LOCKED','COOLING','FROZEN','WAITING_CALCULATION','UNLOCKED','AVAILABLE','SETTLED','PAID','WITHDRAWN')
                      THEN GREATEST(amountNex-cancelledNex,0) ELSE 0 END netNex
            FROM reward_sources
        )
        """;
    String PURCHASE_FILTER = " WHERE (#{filter}='all' OR (#{filter}='direct' AND layerNo=1) OR (#{filter}='extended' AND layerNo BETWEEN 2 AND 7)) ";

    @Select("<script>" + PURCHASE_REWARDS + """
            SELECT rewardId,sourceUserId,sourceUserName,layerNo,orderNo,cycle,orderAmountUsd,
                   amountUsdt,amountNex,currency,status,createdAt,unlockAt,kind,sourceRef,policyVersion,
                   nexUsdtPrice,recoveryPendingUsdt,recoveryPendingNex,statusUsdt,statusNex FROM rewards
            """ + PURCHASE_FILTER
            + "ORDER BY createdAt DESC,rewardId DESC LIMIT #{offset},#{limit}</script>")
    List<PurchaseRewardRow> purchaseRewards(@Param("userId") Long userId,@Param("sandbox") Integer sandbox,
            @Param("sourceEnvironment") String sourceEnvironment,@Param("runId") String runId,
            @Param("snapshotAt") LocalDateTime snapshotAt,@Param("fromInclusive") LocalDateTime fromInclusive,
            @Param("toExclusive") LocalDateTime toExclusive,@Param("filter") String filter,
            @Param("offset") long offset,@Param("limit") long limit);

    @Select("<script>" + PURCHASE_REWARDS + """
        SELECT CASE WHEN layerNo=1 THEN 'direct' ELSE 'extended' END rewardScope,
               COUNT(*) rewardCount,
               COALESCE(SUM(netUsdt),0) amountUsdt,COALESCE(SUM(netNex),0) amountNex,
               COALESCE(SUM(CASE WHEN creditedUsdtAt IS NOT NULL OR UPPER(statusUsdt) IN ('UNLOCKED','AVAILABLE','SETTLED','PAID','WITHDRAWN') THEN netUsdt ELSE 0 END),0) creditedUsdt,
               COALESCE(SUM(CASE WHEN creditedNexAt IS NOT NULL OR UPPER(statusNex) IN ('UNLOCKED','AVAILABLE','SETTLED','PAID','WITHDRAWN') THEN netNex ELSE 0 END),0) creditedNex,
               COALESCE(SUM(CASE WHEN creditedUsdtAt IS NULL AND UPPER(statusUsdt) IN ('PENDING','LOCKED','COOLING','FROZEN','WAITING_CALCULATION') THEN netUsdt ELSE 0 END),0) pendingUsdt,
               COALESCE(SUM(CASE WHEN creditedNexAt IS NULL AND UPPER(statusNex) IN ('PENDING','LOCKED','COOLING','FROZEN','WAITING_CALCULATION') THEN netNex ELSE 0 END),0) pendingNex
          FROM rewards GROUP BY CASE WHEN layerNo=1 THEN 'direct' ELSE 'extended' END
        </script>
        """)
    List<PurchaseSummaryRow> purchaseSummary(@Param("userId") Long userId,@Param("sandbox") Integer sandbox,
            @Param("sourceEnvironment") String sourceEnvironment,@Param("runId") String runId,
            @Param("snapshotAt") LocalDateTime snapshotAt,@Param("fromInclusive") LocalDateTime fromInclusive,
            @Param("toExclusive") LocalDateTime toExclusive);

    @Select("SELECT source_ref sourceRef,CAST(source_device_id AS CHAR) sourceDeviceId,recovery_pending_usdt recoveryPendingUSDT,recovery_pending_nex recoveryPendingNEX FROM nx_direct_referral_settlement WHERE usdt_event_id=#{id} OR nex_event_id=#{id}")
    java.util.Map<String,Object> directReferralSnapshot(Long id);
    @Select("SELECT voucher_name FROM nx_growth_voucher WHERE voucher_id=#{id} AND is_deleted=0 LIMIT 1")
    String voucherDisplayName(@Param("id") String id);

    @Select("SELECT name FROM nx_admin_device_sku WHERE sku_id=#{id} AND is_deleted=0 LIMIT 1")
    String skuDisplayName(@Param("id") String id);

    @Select("SELECT sandbox,v_rank vRank FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    UserScope userScope(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*) FROM nx_user u
             WHERE u.id=#{userId}
               AND REPLACE(TRIM(COALESCE(u.country_code,'')),'+','')=REPLACE(#{countryCode},'+','')
               AND u.phone=#{phone} AND u.sandbox=1
               AND u.status='ACTIVE' AND u.is_deleted=0
            """)
    int developmentUserScope(@Param("userId") Long userId,
                             @Param("countryCode") String countryCode,
                             @Param("phone") String phone);

    /**
     * App leaderboard read projection. Its eligibility predicates deliberately
     * mirror F4's settlement candidates: unlocked commission facts only,
     * configured minimum, and member-level risk/disqualification exclusions.
     * Time bounds are supplied by the service's UTC settlement calendar rather
     * than being re-derived from the MySQL session clock.
     */
    String LEADERBOARD_ELIGIBLE_SQL = """
            <script>
            WITH earned AS (
              SELECT ce.user_id,SUM(ce.amount_usdt) earned_usdt
                FROM nx_commission_event ce JOIN nx_user u ON u.id=ce.user_id
               WHERE ce.is_deleted=0 AND u.is_deleted=0 AND u.status='ACTIVE' AND u.sandbox=#{sandbox}
                 AND UPPER(ce.status)='UNLOCKED'
                 AND ce.created_at &lt;= #{snapshotAt}
                 AND LOWER(ce.commission_type) IN
                   ('unilevel','network','binary','peer','cultivation','leadership','genesis','direct_purchase','direct_device_earning')
                 <if test="fromInclusive != null">AND ce.created_at &gt;= #{fromInclusive}</if>
                 <if test="toExclusive != null">AND ce.created_at &lt; #{toExclusive}</if>
                 AND NOT EXISTS (
                    SELECT 1 FROM nx_team_leaderboard_action a
                     WHERE a.is_deleted=0 AND LOWER(a.period)=LOWER(#{actionPeriod})
                       AND a.member_user_id=ce.user_id
                       AND UPPER(a.action_type) IN ('FRAUD','DISQUALIFIED','RISK')
                 )
               GROUP BY ce.user_id
              HAVING SUM(ce.amount_usdt) &gt;= #{minVolumeUsd}
            ), ranked AS (
              SELECT e.user_id,e.earned_usdt,
                     ROW_NUMBER() OVER (ORDER BY e.earned_usdt DESC,e.user_id ASC) rank_no
                FROM earned e
            )
            SELECT r.rank_no `rank`,u.id userId,u.nickname,u.v_rank vRank,r.earned_usdt earnedUsdt,
                   (SELECT COUNT(*) FROM nx_user c WHERE c.sponsor_user_id=u.id AND c.sandbox=u.sandbox
                     AND c.status='ACTIVE' AND c.is_deleted=0) directs,
                   (SELECT COUNT(*) FROM nx_team_member tm WHERE tm.user_id=u.id AND tm.is_deleted=0) teamSize,
                   CASE WHEN EXISTS(SELECT 1 FROM nx_user_device d WHERE d.user_id=u.id AND d.is_deleted=0) THEN 1 ELSE 0 END hasDevice
              FROM ranked r JOIN nx_user u ON u.id=r.user_id
             ORDER BY r.rank_no LIMIT #{limit}
            </script>
            """;

    @Select(LEADERBOARD_ELIGIBLE_SQL)
    List<LeaderboardRow> leaderboardEligible(@Param("actionPeriod") String actionPeriod,
                                             @Param("sandbox") Integer sandbox,
                                             @Param("fromInclusive") LocalDateTime fromInclusive,
                                             @Param("toExclusive") LocalDateTime toExclusive,
                                             @Param("minVolumeUsd") BigDecimal minVolumeUsd,
                                             @Param("limit") int limit,
                                             @Param("snapshotAt") LocalDateTime snapshotAt);

    @Select("""
            SELECT ce.id,ce.commission_type commissionType,ce.source_user_id sourceUserId,
                   COALESCE(CASE WHEN ce.source_user_name REGEXP '[^[:space:]]' THEN ce.source_user_name END,
                            source.nickname) sourceUserName,ce.layer_no layerNo,ce.order_no orderNo,
                   ce.order_amount_usd orderAmountUsd,ce.amount_usdt amountUsdt,ce.amount_nex amountNex,
                   ce.status,ce.created_at createdAt,ce.unlock_at unlockAt
              FROM nx_commission_event ce
              LEFT JOIN nx_user u ON u.id=ce.user_id
              LEFT JOIN nx_user source ON source.id=ce.source_user_id AND source.sandbox=u.sandbox AND source.is_deleted=0
             WHERE ce.user_id=#{userId} AND ce.is_deleted=0 AND ce.created_at <= #{snapshotAt}
             ORDER BY ce.created_at DESC,ce.id DESC LIMIT #{offset},#{limit}
            """)
    List<CommissionRow> commissionEvents(@Param("userId") Long userId, @Param("snapshotAt") LocalDateTime snapshotAt, @Param("offset") long offset,
                                         @Param("limit") long limit);

    @Select("SELECT COUNT(*) FROM nx_commission_event WHERE user_id=#{userId} AND is_deleted=0 AND created_at <= #{snapshotAt}")
    long commissionEventCount(@Param("userId") Long userId, @Param("snapshotAt") LocalDateTime snapshotAt);

    @Select("""
            SELECT COALESCE(SUM(ce.amount_usdt),0) totalUsdt,
                   COALESCE(SUM(ce.amount_nex),0) totalNex,
                   COALESCE(SUM(CASE WHEN LOWER(ce.commission_type) IN ('direct','network','unilevel','direct_purchase','direct_device_earning')
                                      AND ce.layer_no=1 THEN ce.amount_usdt ELSE 0 END),0) directUsdt,
                   COALESCE(SUM(ce.amount_usdt),0)
                     - COALESCE(SUM(CASE WHEN LOWER(ce.commission_type) IN ('direct','network','unilevel','direct_purchase','direct_device_earning')
                                          AND ce.layer_no=1 THEN ce.amount_usdt ELSE 0 END),0) extendedUsdt,
                   COUNT(DISTINCT CASE WHEN ce.source_user_id IS NOT NULL THEN ce.source_user_id END) contributorCount
             FROM nx_commission_event ce
             WHERE ce.user_id=#{userId} AND ce.is_deleted=0 AND ce.created_at <= #{snapshotAt}
               AND UPPER(ce.status) NOT IN ('REJECTED','REVERSED','ROLLBACK','RECOVERY_PENDING')
            """)
    CommissionSummaryRow commissionSummary(@Param("userId") Long userId,
                                           @Param("snapshotAt") LocalDateTime snapshotAt);

    /**
     * F2 unilevel read model.  The user join is intentional: commission_event
     * has no environment column, so the authenticated user's sandbox namespace
     * is the authoritative isolation boundary for this projection.
     */
    @Select("""
            SELECT ce.id,ce.source_user_id sourceUserId,COALESCE(ce.source_user_name,source.nickname) sourceUserName,
                   ce.layer_no layerNo,ce.order_no orderNo,
                   DATE_FORMAT(ce.created_at,'%x-W%v') cycle,
                   ce.order_amount_usd orderAmountUsd,ce.amount_usdt amountUsdt,ce.amount_nex amountNex,
                   ce.currency,ce.status,ce.created_at createdAt,ce.unlock_at unlockAt
              FROM nx_commission_event ce JOIN nx_user u ON u.id=ce.user_id
              LEFT JOIN nx_user source ON source.id=ce.source_user_id AND source.sandbox=u.sandbox
             WHERE ce.user_id=#{userId} AND ce.is_deleted=0
               AND u.status='ACTIVE' AND u.is_deleted=0 AND u.sandbox=#{sandbox}
               AND ce.created_at <= #{snapshotAt}
               AND LOWER(ce.commission_type)='network'
               AND (#{fromInclusive} IS NULL OR ce.created_at >= #{fromInclusive})
               AND (#{toExclusive} IS NULL OR ce.created_at < #{toExclusive})
             ORDER BY ce.created_at DESC,ce.id DESC LIMIT #{offset},#{limit}
            """)
    List<UnilevelRow> unilevelEvents(@Param("userId") Long userId, @Param("sandbox") Integer sandbox,
                                     @Param("snapshotAt") LocalDateTime snapshotAt,
                                     @Param("fromInclusive") LocalDateTime fromInclusive,
                                     @Param("toExclusive") LocalDateTime toExclusive,
                                     @Param("offset") long offset,
                                     @Param("limit") long limit);

    @Select("""
            SELECT COUNT(*) FROM nx_commission_event ce JOIN nx_user u ON u.id=ce.user_id
             WHERE ce.user_id=#{userId} AND ce.is_deleted=0
               AND u.status='ACTIVE' AND u.is_deleted=0 AND u.sandbox=#{sandbox}
               AND ce.created_at <= #{snapshotAt}
               AND LOWER(ce.commission_type)='network'
               AND (#{fromInclusive} IS NULL OR ce.created_at >= #{fromInclusive})
               AND (#{toExclusive} IS NULL OR ce.created_at < #{toExclusive})
            """)
    long unilevelEventCount(@Param("userId") Long userId, @Param("sandbox") Integer sandbox,
                            @Param("snapshotAt") LocalDateTime snapshotAt,
                            @Param("fromInclusive") LocalDateTime fromInclusive,
                            @Param("toExclusive") LocalDateTime toExclusive);

    @Select("""
            SELECT COALESCE(SUM(CASE WHEN ce.layer_no=1 THEN ce.amount_usdt ELSE 0 END),0) directUsdt,
                   COALESCE(SUM(CASE WHEN ce.layer_no=1 THEN ce.amount_nex ELSE 0 END),0) directNex,
                   SUM(CASE WHEN ce.layer_no=1 THEN 1 ELSE 0 END) directCount,
                   COALESCE(SUM(CASE WHEN ce.layer_no>1 THEN ce.amount_usdt ELSE 0 END),0) extendedUsdt,
                   COALESCE(SUM(CASE WHEN ce.layer_no>1 THEN ce.amount_nex ELSE 0 END),0) extendedNex,
                   SUM(CASE WHEN ce.layer_no>1 THEN 1 ELSE 0 END) extendedCount
              FROM nx_commission_event ce JOIN nx_user u ON u.id=ce.user_id
             WHERE ce.user_id=#{userId} AND ce.is_deleted=0
               AND u.status='ACTIVE' AND u.is_deleted=0 AND u.sandbox=#{sandbox}
               AND ce.created_at <= #{snapshotAt}
               AND LOWER(ce.commission_type)='network'
               AND (#{fromInclusive} IS NULL OR ce.created_at >= #{fromInclusive})
               AND (#{toExclusive} IS NULL OR ce.created_at < #{toExclusive})
            """)
    UnilevelSplitRow unilevelSplit(@Param("userId") Long userId, @Param("sandbox") Integer sandbox,
                                   @Param("snapshotAt") LocalDateTime snapshotAt,
                                   @Param("fromInclusive") LocalDateTime fromInclusive,
                                   @Param("toExclusive") LocalDateTime toExclusive);

    @Select("""
            SELECT CAST(REPLACE(UPPER(u.v_rank),'V','') AS UNSIGNED) vRank,COUNT(*) people,
                   COALESCE(MAX(c.leadership_votes),0) votes
              FROM nx_user u LEFT JOIN nx_v_rank_config c ON UPPER(c.rank_code)=UPPER(u.v_rank)
               AND c.status=1 AND c.is_deleted=0
             WHERE u.sandbox=#{sandbox} AND u.status='ACTIVE' AND u.is_deleted=0
               AND CAST(REPLACE(UPPER(u.v_rank),'V','') AS UNSIGNED) >= #{minRank}
               AND COALESCE(c.leadership_votes,0) > 0
             GROUP BY u.v_rank ORDER BY vRank
            """)
    List<RankDistributionRow> rankDistribution(@Param("sandbox") Integer sandbox, @Param("minRank") int minRank);

    @Select("""
            SELECT COALESCE(SUM(ce.amount_usdt),0) currentPoolUsdt
              FROM nx_commission_event ce JOIN nx_user u ON u.id=ce.user_id
             WHERE ce.is_deleted=0 AND u.is_deleted=0 AND u.sandbox=#{sandbox}
               AND LOWER(ce.commission_type)='leadership'
               AND ce.created_at>=#{fromInclusive} AND ce.created_at<#{toExclusive}
            """)
    BigDecimal currentLeadershipPool(@Param("sandbox") Integer sandbox,
                                     @Param("fromInclusive") LocalDateTime fromInclusive,
                                     @Param("toExclusive") LocalDateTime toExclusive);

    @Select(TeamCommissionMapper.LEADERSHIP_POOL_SUMMARY)
    java.util.Map<String, Object> leadershipPoolSummary();

    @Select("""
            SELECT DATE_FORMAT(created_at,'%x-W%v') weekId,SUM(amount_usdt) payoutUsdt
              FROM nx_commission_event
             WHERE user_id=#{userId} AND is_deleted=0 AND LOWER(commission_type)='leadership'
               AND created_at<#{beforeExclusive}
             GROUP BY DATE_FORMAT(created_at,'%x-W%v') ORDER BY weekId DESC LIMIT 12
            """)
    List<LeadershipHistoryRow> leadershipHistory(@Param("userId") Long userId,
                                                 @Param("beforeExclusive") LocalDateTime beforeExclusive);

    @Select("""
            SELECT ce.commission_type commissionType, ce.status, COUNT(*) eventCount,
                   COALESCE(SUM(CASE WHEN UPPER(ce.status) NOT IN ('REJECTED','REVERSED','ROLLBACK','RECOVERY_PENDING') THEN ce.amount_usdt ELSE 0 END),0) totalUsdt,
                   COALESCE(SUM(CASE WHEN UPPER(ce.status) NOT IN ('REJECTED','REVERSED','ROLLBACK','RECOVERY_PENDING') THEN ce.amount_nex ELSE 0 END),0) totalNex,
                   COALESCE(SUM(CASE WHEN UPPER(ce.status) NOT IN ('REJECTED','REVERSED','ROLLBACK','RECOVERY_PENDING') AND ce.created_at >= #{monthFrom} AND ce.created_at < #{monthTo}
                     THEN ce.amount_usdt ELSE 0 END),0) monthUsdt,
                   COALESCE(SUM(CASE WHEN UPPER(ce.status) NOT IN ('REJECTED','REVERSED','ROLLBACK','RECOVERY_PENDING') AND ce.created_at >= #{monthFrom} AND ce.created_at < #{monthTo}
                     THEN ce.amount_nex ELSE 0 END),0) monthNex,
                   COALESCE(SUM(CASE WHEN UPPER(ce.status) NOT IN ('REJECTED','REVERSED','ROLLBACK','RECOVERY_PENDING') AND ce.created_at >= #{todayFrom} AND ce.created_at < #{todayTo}
                     THEN ce.amount_usdt ELSE 0 END),0) todayUsdt,
                   MIN(ce.unlock_at) nextUnlockAt
            FROM nx_commission_event ce WHERE ce.user_id=#{userId} AND ce.is_deleted=0
              AND ce.created_at <= #{snapshotAt}
            GROUP BY ce.commission_type, ce.status
            """)
    List<CommissionBucket> commissionBuckets(@Param("userId") Long userId,
            @Param("monthFrom") LocalDateTime monthFrom, @Param("monthTo") LocalDateTime monthTo,
            @Param("todayFrom") LocalDateTime todayFrom, @Param("todayTo") LocalDateTime todayTo,
            @Param("snapshotAt") LocalDateTime snapshotAt);

    record CommissionBucket(String commissionType, String status, Integer eventCount,
            BigDecimal totalUsdt, BigDecimal totalNex, BigDecimal monthUsdt, BigDecimal monthNex,
            BigDecimal todayUsdt, LocalDateTime nextUnlockAt) {}

    record UserScope(Integer sandbox, String vRank) { }
    record PurchaseRewardRow(String rewardId,Long sourceUserId,String sourceUserName,Integer layerNo,String orderNo,
            String cycle,BigDecimal orderAmountUsd,BigDecimal amountUsdt,BigDecimal amountNex,String currency,
            String status,LocalDateTime createdAt,LocalDateTime unlockAt,String kind,String sourceRef,Long policyVersion,
            BigDecimal nexUsdtPrice,BigDecimal recoveryPendingUsdt,BigDecimal recoveryPendingNex,
            String statusUsdt,String statusNex) { }
    record PurchaseSummaryRow(String rewardScope,long rewardCount,BigDecimal amountUsdt,BigDecimal amountNex,
            BigDecimal creditedUsdt,BigDecimal creditedNex,BigDecimal pendingUsdt,BigDecimal pendingNex) { }
    record LeaderboardRow(Integer rank, Long userId, String nickname, String vRank, BigDecimal earnedUsdt,
                          Integer directs, Integer teamSize, Integer hasDevice) { }
    record CommissionRow(Long id, String commissionType, Long sourceUserId, String sourceUserName,
                         Integer layerNo, String orderNo, BigDecimal orderAmountUsd, BigDecimal amountUsdt,
                         BigDecimal amountNex, String status, LocalDateTime createdAt, LocalDateTime unlockAt) { }
    record CommissionSummaryRow(BigDecimal totalUsdt, BigDecimal totalNex, BigDecimal directUsdt,
                                BigDecimal extendedUsdt, Integer contributorCount) { }
    record UnilevelRow(Long id, Long sourceUserId, String sourceUserName, Integer layerNo, String orderNo,
                       String cycle, BigDecimal orderAmountUsd, BigDecimal amountUsdt, BigDecimal amountNex,
                       String currency, String status, LocalDateTime createdAt, LocalDateTime unlockAt) { }
    record UnilevelSplitRow(BigDecimal directUsdt, BigDecimal directNex, Integer directCount,
                            BigDecimal extendedUsdt, BigDecimal extendedNex, Integer extendedCount) { }
    record RankDistributionRow(Integer vRank, Integer people, Integer votes) { }
    record LeadershipHistoryRow(String weekId, BigDecimal payoutUsdt) { }
}
