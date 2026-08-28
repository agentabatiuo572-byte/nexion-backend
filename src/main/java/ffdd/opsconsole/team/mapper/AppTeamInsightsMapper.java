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

    @Select("""
            WITH earned AS (
              SELECT ce.user_id,SUM(ce.amount_usdt) earned_usdt
                FROM nx_commission_event ce JOIN nx_user u ON u.id=ce.user_id
               WHERE ce.is_deleted=0 AND u.is_deleted=0 AND u.status='ACTIVE' AND u.sandbox=#{sandbox}
                 AND (#{period}='all'
                   OR (#{period}='today' AND ce.created_at>=CURDATE())
                   OR (#{period}='week' AND ce.created_at>=DATE_SUB(CURDATE(),INTERVAL WEEKDAY(CURDATE()) DAY))
                   OR (#{period}='month' AND ce.created_at>=DATE_FORMAT(CURDATE(),'%Y-%m-01')))
               GROUP BY ce.user_id
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
             ORDER BY r.rank_no LIMIT 100
            """)
    List<LeaderboardRow> leaderboard(@Param("period") String period, @Param("sandbox") Integer sandbox);

    @Select("""
            SELECT ce.id,ce.commission_type commissionType,ce.source_user_id sourceUserId,
                   ce.source_user_name sourceUserName,ce.layer_no layerNo,ce.order_no orderNo,
                   ce.order_amount_usd orderAmountUsd,ce.amount_usdt amountUsdt,ce.amount_nex amountNex,
                   ce.status,ce.created_at createdAt,ce.unlock_at unlockAt
              FROM nx_commission_event ce
             WHERE ce.user_id=#{userId} AND ce.is_deleted=0
             ORDER BY ce.created_at DESC,ce.id DESC LIMIT #{limit}
            """)
    List<CommissionRow> commissionEvents(@Param("userId") Long userId, @Param("limit") int limit);

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
               AND LOWER(ce.commission_type)='network'
               AND (#{period}='all'
                 OR (#{period}='today' AND ce.created_at>=CURDATE())
                 OR (#{period}='week' AND ce.created_at>=DATE_SUB(CURDATE(),INTERVAL WEEKDAY(CURDATE()) DAY))
                 OR (#{period}='month' AND ce.created_at>=DATE_FORMAT(CURDATE(),'%Y-%m-01')))
             ORDER BY ce.created_at DESC,ce.id DESC LIMIT 500
            """)
    List<UnilevelRow> unilevelEvents(@Param("userId") Long userId, @Param("sandbox") Integer sandbox,
                                     @Param("period") String period);

    @Select("""
            SELECT CAST(REPLACE(UPPER(u.v_rank),'V','') AS UNSIGNED) vRank,COUNT(*) people,
                   COALESCE(MAX(c.leadership_votes),0) votes
              FROM nx_user u LEFT JOIN nx_v_rank_config c ON UPPER(c.rank_code)=UPPER(u.v_rank)
               AND c.status=1 AND c.is_deleted=0
             WHERE u.sandbox=#{sandbox} AND u.status='ACTIVE' AND u.is_deleted=0
             GROUP BY u.v_rank ORDER BY vRank
            """)
    List<RankDistributionRow> rankDistribution(@Param("sandbox") Integer sandbox);

    @Select("""
            SELECT COALESCE(SUM(ce.amount_usdt),0) currentPoolUsdt
              FROM nx_commission_event ce JOIN nx_user u ON u.id=ce.user_id
             WHERE ce.is_deleted=0 AND u.is_deleted=0 AND u.sandbox=#{sandbox}
               AND LOWER(ce.commission_type)='leadership'
               AND ce.created_at>=DATE_SUB(CURDATE(),INTERVAL WEEKDAY(CURDATE()) DAY)
            """)
    BigDecimal currentLeadershipPool(@Param("sandbox") Integer sandbox);

    @Select("""
            SELECT DATE_FORMAT(created_at,'%x-W%v') weekId,SUM(amount_usdt) payoutUsdt
              FROM nx_commission_event
             WHERE user_id=#{userId} AND is_deleted=0 AND LOWER(commission_type)='leadership'
               AND created_at<DATE_SUB(CURDATE(),INTERVAL WEEKDAY(CURDATE()) DAY)
             GROUP BY DATE_FORMAT(created_at,'%x-W%v') ORDER BY weekId DESC LIMIT 12
            """)
    List<LeadershipHistoryRow> leadershipHistory(@Param("userId") Long userId);

    record UserScope(Integer sandbox, String vRank) { }
    record LeaderboardRow(Integer rank, Long userId, String nickname, String vRank, BigDecimal earnedUsdt,
                          Integer directs, Integer teamSize, Integer hasDevice) { }
    record CommissionRow(Long id, String commissionType, Long sourceUserId, String sourceUserName,
                         Integer layerNo, String orderNo, BigDecimal orderAmountUsd, BigDecimal amountUsdt,
                         BigDecimal amountNex, String status, LocalDateTime createdAt, LocalDateTime unlockAt) { }
    record UnilevelRow(Long id, Long sourceUserId, String sourceUserName, Integer layerNo, String orderNo,
                       String cycle, BigDecimal orderAmountUsd, BigDecimal amountUsdt, BigDecimal amountNex,
                       String currency, String status, LocalDateTime createdAt, LocalDateTime unlockAt) { }
    record RankDistributionRow(Integer vRank, Integer people, Integer votes) { }
    record LeadershipHistoryRow(String weekId, BigDecimal payoutUsdt) { }
}
