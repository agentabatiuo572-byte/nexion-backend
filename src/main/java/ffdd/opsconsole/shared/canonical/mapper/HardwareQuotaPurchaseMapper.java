package ffdd.opsconsole.shared.canonical.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Inherited by device settlement mappers so every purchase holds the same F4b tier lock. */
// Statement-only mixin across quota, member and order tables; it has no single CRUD entity.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface HardwareQuotaPurchaseMapper {
    @Select("""
            SELECT id,quota_code AS quotaCode,product_no AS productNo,direct_refs AS directRefs,
                   month_volume_usd AS monthVolumeUsd,monthly_quota AS monthlyQuota,
                   unlock_mode AS unlockMode,status
              FROM nx_team_hardware_quota_tier
             WHERE product_no=#{productNo} AND is_deleted=0 ORDER BY id FOR UPDATE
            """)
    List<Tier> lockHardwarePurchaseTiers(@Param("productNo") String productNo);

    @Select("""
            WITH RECURSIVE subtree AS (
                SELECT tm.member_user_id, owner.sandbox, 1 AS depth
                  FROM nx_team_member tm
                  JOIN nx_user owner ON owner.id=#{userId}
                   AND owner.status='ACTIVE' AND owner.is_deleted=0
                  JOIN nx_user direct ON direct.id=tm.member_user_id
                   AND direct.sandbox=owner.sandbox AND direct.status='ACTIVE' AND direct.is_deleted=0
                 WHERE tm.user_id=owner.id AND tm.level=1 AND tm.is_deleted=0
                UNION ALL
                SELECT child_member.member_user_id, s.sandbox, s.depth + 1
                  FROM subtree s
                  JOIN nx_team_member child_member ON child_member.user_id=s.member_user_id
                   AND child_member.level=1 AND child_member.is_deleted=0
                  JOIN nx_user child ON child.id=child_member.member_user_id
                   AND child.sandbox=s.sandbox AND child.status='ACTIVE' AND child.is_deleted=0
                 WHERE s.depth < 7
            )
            SELECT (SELECT COUNT(*) FROM nx_user child
                     JOIN nx_user owner ON owner.id=#{userId}
                      AND owner.status='ACTIVE' AND owner.is_deleted=0
                    WHERE child.sponsor_user_id=owner.id AND child.sandbox=owner.sandbox
                      AND child.status='ACTIVE' AND child.is_deleted=0) AS activeDirect,
                   COALESCE(SUM(o.subtotal_usdt),0) AS monthlyVolumeUsd
              FROM subtree s
              LEFT JOIN nx_order o ON o.user_id=s.member_user_id
               AND o.payment_status IN ('PAID','CONFIRMED','SUCCESS')
               AND o.order_status NOT IN ('REFUNDED','CHARGEBACK')
               AND COALESCE(o.paid_at,o.created_at)>=DATE_ADD(DATE_FORMAT(UTC_TIMESTAMP(),'%Y-%m-01'),INTERVAL 8 HOUR)
               AND COALESCE(o.paid_at,o.created_at)<DATE_ADD(
                    DATE_ADD(DATE_FORMAT(UTC_TIMESTAMP(),'%Y-%m-01'),INTERVAL 1 MONTH),INTERVAL 8 HOUR)
               AND o.is_deleted=0
            """)
    Facts hardwarePurchaseFacts(@Param("userId") Long userId);

    @Select("""
            SELECT quantity FROM nx_team_hardware_quota_usage
             WHERE quota_tier_id=#{tierId} AND status='ACTIVE' AND is_deleted=0
               AND occurred_at>=#{from} AND occurred_at<#{until} ORDER BY id FOR UPDATE
            """)
    List<Integer> lockHardwarePurchaseUsage(@Param("tierId") Long tierId,
            @Param("from") LocalDateTime from, @Param("until") LocalDateTime until);

    @Insert("""
            INSERT INTO nx_team_hardware_quota_usage
              (quota_tier_id,quota_code,product_no,user_id,order_no,usage_type,quantity,status,occurred_at,remark)
            VALUES (#{tier.id},#{tier.quotaCode},#{tier.productNo},#{userId},#{orderNo},'SOLD',1,'ACTIVE',#{occurredAt},'Device purchase settlement')
            """)
    int recordHardwarePurchase(@Param("tier") Tier tier, @Param("userId") Long userId,
            @Param("orderNo") String orderNo, @Param("occurredAt") LocalDateTime occurredAt);

    record Tier(Long id, String quotaCode, String productNo, Integer directRefs,
                BigDecimal monthVolumeUsd, Integer monthlyQuota, String unlockMode, Integer status) { }
    record Facts(Long activeDirect, BigDecimal monthlyVolumeUsd) { }
}
