package ffdd.opsconsole.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

/** F3 server-authoritative binary settlement persistence boundary. */
@Mapper
public interface BinaryCommissionSettlementMapper extends BaseMapper<Object> {

    @Select("SELECT id FROM nx_user WHERE id=#{ownerUserId} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    Long lockActiveOwner(@Param("ownerUserId") Long ownerUserId);

    @Select("""
            SELECT id FROM nx_team_member
             WHERE user_id=#{ownerUserId} AND member_user_id=#{memberUserId}
               AND level=1 AND is_deleted=0
             ORDER BY id LIMIT 1 FOR UPDATE
            """)
    Long lockDirectMember(@Param("ownerUserId") Long ownerUserId,
                          @Param("memberUserId") Long memberUserId);

    @Select("""
            SELECT owner_user_id ownerUserId,member_user_id memberUserId,leg
              FROM nx_binary_leg_assignment
             WHERE owner_user_id=#{ownerUserId} AND member_user_id=#{memberUserId}
             LIMIT 1 FOR UPDATE
            """)
    BinaryLegAssignmentRow findAssignmentForUpdate(
            @Param("ownerUserId") Long ownerUserId,
            @Param("memberUserId") Long memberUserId);

    @Insert("""
            INSERT IGNORE INTO nx_binary_leg_assignment
              (owner_user_id,member_user_id,leg,assigned_by_admin_id,assigned_by,
               assigned_at,created_at,updated_at)
            VALUES
              (#{ownerUserId},#{memberUserId},#{leg},#{assignedByAdminId},#{assignedBy},NOW(),NOW(),NOW())
            """)
    int insertAssignment(@Param("ownerUserId") Long ownerUserId,
                         @Param("memberUserId") Long memberUserId,
                         @Param("leg") String leg,
                         @Param("assignedByAdminId") Long assignedByAdminId,
                         @Param("assignedBy") String assignedBy);

    @Select("""
            SELECT COUNT(DISTINCT member_user_id) FROM nx_team_member
             WHERE user_id=#{ownerUserId} AND level=1 AND is_deleted=0
            """)
    int countDirectMembers(@Param("ownerUserId") Long ownerUserId);

    @Select("""
            SELECT a.owner_user_id ownerUserId,a.member_user_id memberUserId,a.leg
              FROM nx_binary_leg_assignment a
              JOIN nx_team_member m
                ON m.user_id=a.owner_user_id AND m.member_user_id=a.member_user_id
               AND m.level=1 AND m.is_deleted=0
             WHERE a.owner_user_id=#{ownerUserId}
             ORDER BY a.leg,a.member_user_id
             FOR UPDATE
            """)
    List<BinaryLegAssignmentRow> listAssignmentsForUpdate(@Param("ownerUserId") Long ownerUserId);

    @Select("""
            WITH RECURSIVE assigned_tree AS (
              SELECT a.member_user_id root_member_user_id,
                     a.member_user_id node_user_id,
                     a.leg,
                     0 depth,
                     CAST(a.member_user_id AS CHAR(2048)) path
                FROM nx_binary_leg_assignment a
               WHERE a.owner_user_id=#{ownerUserId}
              UNION ALL
              SELECT t.root_member_user_id,
                     m.member_user_id,
                     t.leg,
                     t.depth+1,
                     CONCAT(t.path,',',m.member_user_id)
                FROM assigned_tree t
                JOIN nx_team_member m
                  ON m.user_id=t.node_user_id AND m.level=1 AND m.is_deleted=0
               WHERE t.depth<63
                 AND FIND_IN_SET(CAST(m.member_user_id AS CHAR),t.path)=0
            )
            SELECT o.order_no orderNo,
                   o.user_id orderUserId,
                   MIN(t.root_member_user_id) rootMemberUserId,
                   MIN(t.leg) leg,
                   o.amount_usdt amountUsdt,
                   COALESCE(o.paid_at,o.updated_at) paidAt,
                   COUNT(DISTINCT t.root_member_user_id) mappedRootCount
              FROM nx_order o
              JOIN assigned_tree t ON t.node_user_id=o.user_id
             WHERE o.is_deleted=0
               AND o.amount_usdt>0
               AND UPPER(COALESCE(o.payment_status,'')) IN ('PAID','CONFIRMED','SUCCESS')
               AND UPPER(COALESCE(o.order_status,'')) NOT IN
                   ('REFUNDED','CHARGEBACK','CANCELLED','FAILED','EXPIRED','REVERSED')
               AND COALESCE(o.paid_at,o.updated_at)>=#{monthStart}
               AND COALESCE(o.paid_at,o.updated_at)<#{monthEnd}
             GROUP BY o.order_no,o.user_id,o.amount_usdt,COALESCE(o.paid_at,o.updated_at)
             ORDER BY o.order_no
            """)
    List<PaidOrderVolumeCandidate> listPaidOrderCandidates(
            @Param("ownerUserId") Long ownerUserId,
            @Param("monthStart") LocalDateTime monthStart,
            @Param("monthEnd") LocalDateTime monthEnd);

    @Insert("""
            INSERT IGNORE INTO nx_binary_paid_order_volume
              (order_no,owner_user_id,order_user_id,root_member_user_id,leg,amount_usdt,paid_at,
               status,created_at,updated_at,is_deleted)
            VALUES
              (#{row.orderNo},#{ownerUserId},#{row.orderUserId},#{row.rootMemberUserId},#{row.leg},
               #{row.amountUsdt},#{row.paidAt},'ACTIVE',NOW(),NOW(),0)
            """)
    int insertPaidOrderVolume(@Param("ownerUserId") Long ownerUserId,
                              @Param("row") PaidOrderVolumeCandidate row);

    @Select("""
            SELECT order_no orderNo,order_user_id orderUserId,root_member_user_id rootMemberUserId,
                   leg,amount_usdt amountUsdt,paid_at paidAt,1 mappedRootCount
              FROM nx_binary_paid_order_volume
             WHERE owner_user_id=#{ownerUserId} AND order_no=#{orderNo}
               AND status='ACTIVE' AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    PaidOrderVolumeCandidate findPaidOrderVolumeForUpdate(
            @Param("ownerUserId") Long ownerUserId,
            @Param("orderNo") String orderNo);

    @Select("""
            SELECT COUNT(1)
              FROM nx_binary_paid_order_volume v
              LEFT JOIN nx_order o ON o.order_no=v.order_no
             WHERE v.owner_user_id=#{ownerUserId}
               AND v.status='ACTIVE' AND v.is_deleted=0
               AND (o.id IS NULL OR o.is_deleted<>0
                    OR UPPER(COALESCE(o.payment_status,'')) NOT IN ('PAID','CONFIRMED','SUCCESS')
                    OR UPPER(COALESCE(o.order_status,'')) IN
                       ('REFUNDED','CHARGEBACK','CANCELLED','FAILED','EXPIRED','REVERSED'))
            """)
    int countInvalidPaidOrderVolumes(@Param("ownerUserId") Long ownerUserId);

    @Update("""
            UPDATE nx_binary_paid_order_volume v
            LEFT JOIN nx_order o ON o.order_no=v.order_no
               SET v.status='VOID',v.void_reason='SOURCE_ORDER_NOT_PAID',
                   v.is_deleted=1,v.updated_at=NOW()
             WHERE v.owner_user_id=#{ownerUserId}
               AND v.status='ACTIVE' AND v.is_deleted=0
               AND (o.id IS NULL OR o.is_deleted<>0
                    OR UPPER(COALESCE(o.payment_status,'')) NOT IN ('PAID','CONFIRMED','SUCCESS')
                    OR UPPER(COALESCE(o.order_status,'')) IN
                       ('REFUNDED','CHARGEBACK','CANCELLED','FAILED','EXPIRED','REVERSED'))
               AND NOT EXISTS (
                    SELECT 1 FROM nx_binary_commission_settlement s
                     WHERE s.user_id=v.owner_user_id AND s.is_deleted=0
                       AND s.status<>'BLOCKED' AND s.matched_volume>0
                       AND s.settlement_date>=DATE_FORMAT(v.paid_at,'%Y-%m-01')
                       AND s.settlement_date<DATE_ADD(DATE_FORMAT(v.paid_at,'%Y-%m-01'),INTERVAL 1 MONTH))
            """)
    int voidInvalidUnconsumedPaidOrderVolumes(@Param("ownerUserId") Long ownerUserId);

    @Update("""
            UPDATE nx_binary_paid_order_volume v
            LEFT JOIN nx_order o ON o.order_no=v.order_no
               SET v.status='REVERSAL_REQUIRED',v.void_reason='SOURCE_ORDER_REVERSED_AFTER_SETTLEMENT',
                   v.updated_at=NOW()
             WHERE v.owner_user_id=#{ownerUserId}
               AND v.status='ACTIVE' AND v.is_deleted=0
               AND (o.id IS NULL OR o.is_deleted<>0
                    OR UPPER(COALESCE(o.payment_status,'')) NOT IN ('PAID','CONFIRMED','SUCCESS')
                    OR UPPER(COALESCE(o.order_status,'')) IN
                       ('REFUNDED','CHARGEBACK','CANCELLED','FAILED','EXPIRED','REVERSED'))
               AND EXISTS (
                    SELECT 1 FROM nx_binary_commission_settlement s
                     WHERE s.user_id=v.owner_user_id AND s.is_deleted=0
                       AND s.status<>'BLOCKED' AND s.matched_volume>0
                       AND s.settlement_date>=DATE_FORMAT(v.paid_at,'%Y-%m-01')
                       AND s.settlement_date<DATE_ADD(DATE_FORMAT(v.paid_at,'%Y-%m-01'),INTERVAL 1 MONTH))
            """)
    int markInvalidPaidOrderVolumesReversalRequired(@Param("ownerUserId") Long ownerUserId);

    @Select("""
            SELECT COUNT(1) FROM nx_binary_paid_order_volume
             WHERE owner_user_id=#{ownerUserId}
               AND status='REVERSAL_REQUIRED' AND is_deleted=0
            """)
    int countReversalRequiredVolumes(@Param("ownerUserId") Long ownerUserId);

    @Select("""
            SELECT COALESCE(SUM(CASE WHEN leg='A' THEN amount_usdt ELSE 0 END),0) leftVolume,
                   COALESCE(SUM(CASE WHEN leg='B' THEN amount_usdt ELSE 0 END),0) rightVolume
             FROM nx_binary_paid_order_volume
             WHERE owner_user_id=#{ownerUserId}
               AND status='ACTIVE' AND is_deleted=0
               AND paid_at>=#{monthStart} AND paid_at<#{monthEnd}
            """)
    LegVolumeSnapshot monthlyLegVolumes(
            @Param("ownerUserId") Long ownerUserId,
            @Param("monthStart") LocalDateTime monthStart,
            @Param("monthEnd") LocalDateTime monthEnd);

    @Insert("""
            INSERT IGNORE INTO nx_binary_settlement_mutex
              (owner_user_id,settlement_date,created_at,updated_at)
            VALUES (#{ownerUserId},#{settlementDate},NOW(),NOW())
            """)
    int ensureSettlementMutex(@Param("ownerUserId") Long ownerUserId,
                              @Param("settlementDate") LocalDate settlementDate);

    @Select("""
            SELECT id FROM nx_binary_settlement_mutex
             WHERE owner_user_id=#{ownerUserId} AND settlement_date=#{settlementDate}
             FOR UPDATE
            """)
    Long lockSettlementMutex(@Param("ownerUserId") Long ownerUserId,
                             @Param("settlementDate") LocalDate settlementDate);

    @Select("""
            SELECT id,user_id userId,settlement_date settlementDate,
                   left_user_id leftUserId,right_user_id rightUserId,
                   left_volume leftVolume,right_volume rightVolume,matched_volume matchedVolume,
                   amount_usdt amountUsdt,daily_cap_usdt dailyCapUsdt,
                   commission_event_id commissionEventId,status
              FROM nx_binary_commission_settlement
             WHERE user_id=#{ownerUserId} AND settlement_date=#{settlementDate} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    BinarySettlementRow findSettlementForUpdate(
            @Param("ownerUserId") Long ownerUserId,
            @Param("settlementDate") LocalDate settlementDate);

    @Delete("""
            DELETE FROM nx_binary_commission_settlement
             WHERE user_id=#{ownerUserId} AND settlement_date=#{settlementDate}
               AND status='BLOCKED' AND is_deleted=0
            """)
    int deleteBlockedSettlement(@Param("ownerUserId") Long ownerUserId,
                                @Param("settlementDate") LocalDate settlementDate);

    @Select("""
            SELECT COALESCE(SUM(matched_volume),0)
              FROM nx_binary_commission_settlement
             WHERE user_id=#{ownerUserId} AND settlement_date>=#{monthStart}
               AND settlement_date<DATE_ADD(#{monthStart},INTERVAL 1 MONTH) AND is_deleted=0
               AND status<>'BLOCKED'
            """)
    BigDecimal consumedMatchedInMonth(
            @Param("ownerUserId") Long ownerUserId,
            @Param("monthStart") LocalDate monthStart);

    @Select("""
            SELECT COALESCE(SUM(amount_usdt),0)
              FROM nx_binary_commission_settlement
             WHERE user_id=#{ownerUserId} AND settlement_date=#{settlementDate}
               AND is_deleted=0 AND status<>'BLOCKED'
            """)
    BigDecimal settledAmountOnDate(
            @Param("ownerUserId") Long ownerUserId,
            @Param("settlementDate") LocalDate settlementDate);

    @Insert("""
            INSERT INTO nx_binary_commission_settlement
              (user_id,settlement_date,left_user_id,right_user_id,left_volume,right_volume,
               matched_volume,amount_usdt,daily_cap_usdt,commission_event_id,status,
               created_at,updated_at,is_deleted)
            VALUES
              (#{row.userId},#{row.settlementDate},#{row.leftUserId},#{row.rightUserId},
               #{row.leftVolume},#{row.rightVolume},#{row.matchedVolume},#{row.amountUsdt},
               #{row.dailyCapUsdt},#{row.commissionEventId},#{row.status},NOW(),NOW(),0)
            """)
    int insertSettlement(@Param("row") BinarySettlementRow row);

    @Insert("""
            INSERT INTO nx_commission_event
              (user_id,commission_type,source_user_id,source_user_name,layer_no,order_no,
               order_amount_usd,amount_usdt,amount_nex,currency,status,unlock_at,remark,
               created_at,updated_at,is_deleted)
            VALUES
              (#{row.userId},'binary',NULL,NULL,0,#{row.settlementNo},#{row.matchedVolume},
               #{row.amountUsdt},0,'USDT','COOLING',DATE_ADD(NOW(), INTERVAL #{row.coolingDays} DAY),#{row.remark},NOW(),NOW(),0)
            """)
    int insertBinaryCommissionEvent(@Param("row") BinaryCommissionEventRow row);

    @Select("SELECT LAST_INSERT_ID()")
    Long selectLastInsertId();

    @Update("""
            UPDATE nx_binary_commission_settlement
               SET commission_event_id=#{commissionEventId},updated_at=NOW()
             WHERE user_id=#{ownerUserId} AND settlement_date=#{settlementDate}
               AND commission_event_id IS NULL AND is_deleted=0
            """)
    int linkSettlementEvent(@Param("ownerUserId") Long ownerUserId,
                            @Param("settlementDate") LocalDate settlementDate,
                            @Param("commissionEventId") Long commissionEventId);

    record BinaryLegAssignmentRow(Long ownerUserId, Long memberUserId, String leg) { }

    record PaidOrderVolumeCandidate(
            String orderNo,
            Long orderUserId,
            Long rootMemberUserId,
            String leg,
            BigDecimal amountUsdt,
            LocalDateTime paidAt,
            int mappedRootCount) { }

    record LegVolumeSnapshot(BigDecimal leftVolume, BigDecimal rightVolume) { }

    record BinarySettlementRow(
            Long id,
            Long userId,
            LocalDate settlementDate,
            Long leftUserId,
            Long rightUserId,
            BigDecimal leftVolume,
            BigDecimal rightVolume,
            BigDecimal matchedVolume,
            BigDecimal amountUsdt,
            BigDecimal dailyCapUsdt,
            Long commissionEventId,
            String status) { }

    record BinaryCommissionEventRow(
            Long userId,
            String settlementNo,
            BigDecimal matchedVolume,
            BigDecimal amountUsdt,
            String remark,
            int coolingDays) { }
}
