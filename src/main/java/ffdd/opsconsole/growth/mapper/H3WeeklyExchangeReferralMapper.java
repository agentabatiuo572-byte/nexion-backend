package ffdd.opsconsole.growth.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Canonical read boundary for the two narrow H3 weekly completion sources. */
@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface H3WeeklyExchangeReferralMapper {

    @Select("SELECT effective_at FROM nx_growth_weekly_exchange_referral_rollout WHERE id=1")
    LocalDateTime rolloutEffectiveAt();

    @Select("""
            SELECT o.user_id AS userId,o.exchange_no AS exchangeNo,o.updated_at AS completedAt
              FROM nx_exchange_order o
              JOIN nx_user u ON u.id=o.user_id AND u.status='ACTIVE' AND u.is_deleted=0
                AND COALESCE(u.sandbox,0)=0
             WHERE o.user_id=#{userId} AND o.exchange_no=#{exchangeNo} AND o.is_deleted=0
               AND UPPER(o.status)='COMPLETED'
               AND ((UPPER(o.from_asset)='NEX' AND UPPER(o.to_asset)='USDT')
                 OR (UPPER(o.from_asset)='USDT' AND UPPER(o.to_asset)='NEX'))
             LIMIT 1
            """)
    VerifiedExchange verifiedCompletedExchange(@Param("userId") Long userId, @Param("exchangeNo") String exchangeNo);

    @Select("""
            SELECT sponsor.id AS ownerUserId,member.id AS memberUserId,member.created_at AS registeredAt
              FROM nx_user member
              JOIN nx_user sponsor ON sponsor.id=member.sponsor_user_id
                AND sponsor.status='ACTIVE' AND sponsor.is_deleted=0 AND COALESCE(sponsor.sandbox,0)=0
             WHERE member.id=#{memberUserId} AND member.sponsor_user_id=#{sponsorUserId}
               AND member.status='ACTIVE' AND member.is_deleted=0 AND COALESCE(member.sandbox,0)=0
             LIMIT 1
            """)
    VerifiedReferral verifiedReferralRegistration(
            @Param("memberUserId") Long memberUserId, @Param("sponsorUserId") Long sponsorUserId);

    @Select("""
            SELECT COALESCE((SELECT config_value FROM nx_config_item
                              WHERE config_key='growth.phase.current' AND status=1 AND is_deleted=0 LIMIT 1),'P1') AS phase,
                   GREATEST(TIMESTAMPDIFF(MONTH,u.created_at,NOW()),0) AS accountAgeMonths,
                   DATE_FORMAT(u.created_at,'%x-W%v') AS cohort
              FROM nx_user u
             WHERE u.id=#{userId} AND u.status='ACTIVE' AND u.is_deleted=0 AND COALESCE(u.sandbox,0)=0
             LIMIT 1
            """)
    UserAttribution userAttribution(@Param("userId") Long userId);

    record VerifiedExchange(Long userId, String exchangeNo, LocalDateTime completedAt) { }
    record VerifiedReferral(Long ownerUserId, Long memberUserId, LocalDateTime registeredAt) { }
    record UserAttribution(String phase, Integer accountAgeMonths, String cohort) { }
}
