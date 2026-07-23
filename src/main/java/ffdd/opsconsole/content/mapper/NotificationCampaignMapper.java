package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.infrastructure.NotificationCampaignEntity;
import ffdd.opsconsole.content.domain.AppNotificationView;
import ffdd.opsconsole.content.domain.NotificationActionReceipt;
import ffdd.opsconsole.content.domain.NotificationEventFact;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

public interface NotificationCampaignMapper extends BaseMapper<NotificationCampaignEntity> {
    @Insert("""
            <script>
            INSERT INTO nx_notification (
                biz_no, user_id, type, priority, title, body, cta_label, cta_href,
                read_flag, push_status, push_attempts, next_push_at, created_at, updated_at, is_deleted)
            SELECT #{bizNo}, u.id, 'SYSTEM', 'critical',
                   LEFT(CASE
                       WHEN LOWER(u.language) LIKE 'zh%' THEN '风险披露已更新，请重新确认'
                       WHEN LOWER(u.language) LIKE 'vi%' THEN 'Công bố rủi ro đã được cập nhật'
                       ELSE 'Risk disclosure updated'
                   END, 128),
                   LEFT(CASE
                       WHEN LOWER(u.language) LIKE 'zh%' THEN CONCAT(#{jurisdiction}, ' 风险披露已更新至 ', #{version}, '，请在继续受限操作前重新阅读并确认。')
                       WHEN LOWER(u.language) LIKE 'vi%' THEN CONCAT('Công bố rủi ro ', #{jurisdiction}, ' đã cập nhật lên ', #{version}, '. Vui lòng đọc và xác nhận lại.')
                       ELSE CONCAT(#{jurisdiction}, ' risk disclosure is now ', #{version}, '. Please read and acknowledge it again.')
                   END, 512),
                   CASE WHEN LOWER(u.language) LIKE 'zh%' THEN '立即确认'
                        WHEN LOWER(u.language) LIKE 'vi%' THEN 'Xác nhận ngay'
                        ELSE 'Review now' END,
                   '/pages/me/risk-disclosure',
                   0, 'QUEUED', 0, #{now}, #{now}, #{now}, 0
              FROM nx_user u
              LEFT JOIN nx_kyc_profile k ON k.user_id = u.id AND k.is_deleted = 0
             WHERE u.is_deleted = 0 AND u.status = 'ACTIVE'
               AND UPPER(TRIM(CASE
                     WHEN UPPER(COALESCE(k.status, '')) IN ('APPROVED', 'VERIFIED', 'PASSED')
                          AND COALESCE(k.country, '') &lt;&gt; '' THEN k.country
                     ELSE u.country_code END)) IN
               <foreach item="country" collection="countryAliases" open="(" separator="," close=")">
                 UPPER(#{country})
               </foreach>
            ON DUPLICATE KEY UPDATE biz_no = VALUES(biz_no)
            </script>
            """)
    int insertDisclosureReackNotifications(
            @Param("bizNo") String bizNo,
            @Param("jurisdiction") String jurisdiction,
            @Param("version") String version,
            @Param("countryAliases") List<String> countryAliases,
            @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO nx_notification (
                biz_no, user_id, type, priority, title, body, cta_label, cta_href,
                read_flag, push_status, push_attempts, next_push_at, created_at, updated_at, is_deleted)
            SELECT #{bizNo}, u.id, UPPER(#{kind}), LOWER(#{priority}),
                   LEFT(CASE
                       WHEN LOWER(u.language) LIKE 'vi%' THEN #{titleVi}
                       WHEN LOWER(u.language) LIKE 'zh%' THEN #{titleZh}
                       WHEN #{titleEn} <> '' THEN #{titleEn}
                       ELSE #{titleVi}
                   END, 128),
                   LEFT(CASE
                       WHEN LOWER(u.language) LIKE 'vi%' THEN #{bodyVi}
                       WHEN LOWER(u.language) LIKE 'zh%' THEN #{bodyZh}
                       WHEN #{bodyEn} <> '' THEN #{bodyEn}
                       ELSE #{bodyVi}
                   END, 512),
                   #{ctaLabel}, #{ctaHref},
                   0, 'QUEUED', 0, #{now}, #{now}, #{now}, 0
              FROM nx_user u
             WHERE u.is_deleted = 0
               AND u.status = 'ACTIVE'
               AND (#{language} = 'all' OR LOWER(u.language) LIKE CONCAT(#{language}, '%'))
               AND TIMESTAMPDIFF(DAY, u.created_at, #{now}) > #{registrationDaysMin}
            ON DUPLICATE KEY UPDATE
                biz_no = VALUES(biz_no)
            """)
    int insertCampaignNotifications(
            @Param("bizNo") String bizNo,
            @Param("kind") String kind,
            @Param("priority") String priority,
            @Param("language") String language,
            @Param("registrationDaysMin") int registrationDaysMin,
            @Param("titleZh") String titleZh,
            @Param("bodyZh") String bodyZh,
            @Param("titleVi") String titleVi,
            @Param("bodyVi") String bodyVi,
            @Param("titleEn") String titleEn,
            @Param("bodyEn") String bodyEn,
            @Param("ctaLabel") String ctaLabel,
            @Param("ctaHref") String ctaHref,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_notification
               SET push_status = 'DELIVERED', pushed_at = #{now}, next_push_at = NULL, updated_at = #{now}
             WHERE biz_no = #{bizNo}
               AND is_deleted = 0
               AND push_status = 'QUEUED'
            """)
    int markCampaignNotificationsDelivered(@Param("bizNo") String bizNo, @Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(*)
              FROM nx_notification
             WHERE biz_no = #{bizNo}
               AND is_deleted = 0
            """)
    int countNotificationsByBizNo(@Param("bizNo") String bizNo);

    @Select("""
            SELECT n.id notificationId, n.user_id userId, LOWER(n.type) kind,
                   LOWER(n.priority) priority, COALESCE(n.cta_href, '') ctaHref,
                   (n.read_flag = 1) alreadyRead, #{currentPhase} phase,
                   GREATEST(TIMESTAMPDIFF(MONTH, u.created_at, #{now}), 0) accountAgeMonths,
                   DATE_FORMAT(u.created_at, '%x-W%v') cohort
              FROM nx_notification n
              JOIN nx_user u ON u.id=n.user_id AND u.is_deleted=0
             WHERE n.biz_no=#{bizNo} AND n.is_deleted=0
               AND n.push_status IN ('DELIVERED','READ','SENT','SUCCESS')
             ORDER BY n.id
            """)
    List<NotificationEventFact> selectNotificationEventFactsByBizNo(
            @Param("bizNo") String bizNo,
            @Param("currentPhase") String currentPhase,
            @Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(*)
              FROM nx_user
             WHERE is_deleted = 0
               AND status = 'ACTIVE'
               AND (#{language} = 'all' OR LOWER(language) LIKE CONCAT(#{language}, '%'))
               AND TIMESTAMPDIFF(DAY, created_at, #{now}) > #{registrationDaysMin}
            """)
    long countAudience(
            @Param("language") String language,
            @Param("registrationDaysMin") int registrationDaysMin,
            @Param("now") LocalDateTime now);

    @Select("""
            SELECT campaign_no
              FROM nx_notification_campaign
             WHERE is_deleted = 0
               AND status = 'SCHEDULED'
               AND STR_TO_DATE(REPLACE(schedule_text, 'T', ' '), '%Y-%m-%d %H:%i:%s') <= #{now}
             ORDER BY schedule_text ASC, id ASC
             LIMIT #{limit}
            """)
    List<String> selectDueScheduledCampaignNos(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            UPDATE nx_notification_campaign
               SET name = #{name}, kind = #{kind}, tier = #{tier}, audience = #{audience},
                   reach_label = #{reachLabel}, body_en = #{bodyEn}, body_zh = #{bodyZh}, body_vi = #{bodyVi},
                   cta_label = #{ctaLabel}, cta_href = #{ctaHref}, budget_usd = #{budgetUsd},
                   last_operator = #{operator}, revision = revision + 1, updated_at = #{now}
             WHERE campaign_no = #{campaignNo}
               AND is_deleted = 0
               AND status = 'DRAFT'
               AND revision = #{expectedRevision}
            """)
    int updateDraftIfRevision(
            @Param("campaignNo") String campaignNo,
            @Param("name") String name,
            @Param("kind") String kind,
            @Param("tier") String tier,
            @Param("audience") String audience,
            @Param("reachLabel") String reachLabel,
            @Param("bodyEn") String bodyEn,
            @Param("bodyZh") String bodyZh,
            @Param("bodyVi") String bodyVi,
            @Param("ctaLabel") String ctaLabel,
            @Param("ctaHref") String ctaHref,
            @Param("budgetUsd") java.math.BigDecimal budgetUsd,
            @Param("operator") String operator,
            @Param("expectedRevision") long expectedRevision,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_notification_campaign
               SET status = 'SCHEDULED', schedule_text = #{schedule}, last_operator = #{operator},
                   revision = revision + 1, updated_at = #{now}
             WHERE campaign_no = #{campaignNo}
               AND is_deleted = 0
               AND status = 'DRAFT'
               AND revision = #{expectedRevision}
            """)
    int scheduleDraftIfRevision(
            @Param("campaignNo") String campaignNo,
            @Param("schedule") String schedule,
            @Param("operator") String operator,
            @Param("expectedRevision") long expectedRevision,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_notification_campaign
               SET status = 'SENDING', revision = revision + 1, updated_at = #{now}
             WHERE campaign_no = #{campaignNo}
               AND is_deleted = 0
               AND status = 'SCHEDULED'
            """)
    int claimScheduled(@Param("campaignNo") String campaignNo, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_notification_campaign
               SET status = 'SENDING', revision = revision + 1, updated_at = #{now}
             WHERE campaign_no = #{campaignNo}
               AND is_deleted = 0
               AND status IN ('DRAFT', 'SCHEDULED')
               AND revision = #{expectedRevision}
            """)
    int claimForImmediateDispatch(
            @Param("campaignNo") String campaignNo,
            @Param("expectedRevision") long expectedRevision,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_notification_campaign
               SET status = 'CANCELLED', schedule_text = '已取消', last_operator = #{operator},
                   revision = revision + 1, updated_at = #{now}
             WHERE campaign_no = #{campaignNo}
               AND is_deleted = 0
               AND status = 'SCHEDULED'
               AND revision = #{expectedRevision}
            """)
    int cancelScheduled(
            @Param("campaignNo") String campaignNo,
            @Param("operator") String operator,
            @Param("expectedRevision") long expectedRevision,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_notification_campaign
               SET is_deleted = 1, revision = revision + 1, updated_at = #{now}
             WHERE campaign_no = #{campaignNo}
               AND is_deleted = 0
               AND status IN ('DRAFT', 'CANCELLED')
               AND revision = #{expectedRevision}
            """)
    int softDeleteDraft(
            @Param("campaignNo") String campaignNo,
            @Param("expectedRevision") long expectedRevision,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_notification_campaign
               SET status = CASE
                       WHEN schedule_text REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(:[0-9]{2})?$'
                       THEN 'SCHEDULED' ELSE 'DRAFT' END,
                   updated_at = #{now},
                   revision = revision + 1,
                   last_operator = 'system-recovery'
             WHERE is_deleted = 0
               AND status = 'SENDING'
               AND updated_at < #{staleBefore}
            """)
    int recoverStaleSending(@Param("staleBefore") LocalDateTime staleBefore, @Param("now") LocalDateTime now);

    @Select("""
            <script>
            SELECT id,
                   LOWER(type) AS kind,
                   LOWER(priority) AS priority,
                   title,
                   body,
                   COALESCE(cta_label, '') AS ctaLabel,
                   COALESCE(cta_href, '') AS ctaHref,
                   created_at AS createdAt,
                   CASE WHEN read_flag = 1 THEN COALESCE(read_at, updated_at) ELSE NULL END AS readAt
              FROM nx_notification
             WHERE user_id = #{userId}
               AND is_deleted = 0
               AND push_status IN ('DELIVERED', 'READ', 'SENT', 'SUCCESS')
               <if test='cursorId != null'>AND id &lt; #{cursorId}</if>
               <if test='priority != null and priority != ""'>AND LOWER(priority) = #{priority}</if>
             ORDER BY id DESC
             LIMIT #{limit}
            </script>
            """)
    List<AppNotificationView> selectUserNotifications(
            @Param("userId") Long userId,
            @Param("cursorId") Long cursorId,
            @Param("priority") String priority,
            @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
              FROM nx_notification
             WHERE user_id = #{userId}
               AND is_deleted = 0
               AND read_flag = 0
               AND push_status IN ('DELIVERED', 'READ', 'SENT', 'SUCCESS')
            """)
    long countUnreadForUser(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_notification
               SET read_flag = 1, read_at = COALESCE(read_at, NOW()), push_status = 'READ', updated_at = NOW()
             WHERE id = #{notificationId} AND user_id = #{userId} AND is_deleted = 0 AND read_flag = 0
            """)
    int markUserNotificationRead(@Param("userId") Long userId, @Param("notificationId") Long notificationId);

    @Select("""
            SELECT n.id notificationId, n.user_id userId, LOWER(n.type) kind,
                   LOWER(n.priority) priority, COALESCE(n.cta_href, '') ctaHref,
                   (n.read_flag = 1) alreadyRead,
                   COALESCE((SELECT config_value FROM nx_config_item
                              WHERE config_key='growth.phase.current' AND status=1 AND is_deleted=0 LIMIT 1),'P1') phase,
                   GREATEST(TIMESTAMPDIFF(MONTH, u.created_at, NOW()), 0) accountAgeMonths,
                   DATE_FORMAT(u.created_at, '%x-W%v') cohort
              FROM nx_notification n
              JOIN nx_user u ON u.id=n.user_id AND u.is_deleted=0
             WHERE n.id=#{notificationId} AND n.user_id=#{userId} AND n.is_deleted=0
               AND n.push_status IN ('DELIVERED','READ','SENT','SUCCESS')
             LIMIT 1 FOR UPDATE
            """)
    NotificationEventFact lockNotificationEventFact(
            @Param("userId") Long userId, @Param("notificationId") Long notificationId);

    @Select("""
            SELECT n.id notificationId, n.user_id userId, LOWER(n.type) kind,
                   LOWER(n.priority) priority, COALESCE(n.cta_href, '') ctaHref,
                   FALSE alreadyRead,
                   COALESCE((SELECT config_value FROM nx_config_item
                              WHERE config_key='growth.phase.current' AND status=1 AND is_deleted=0 LIMIT 1),'P1') phase,
                   GREATEST(TIMESTAMPDIFF(MONTH, u.created_at, NOW()), 0) accountAgeMonths,
                   DATE_FORMAT(u.created_at, '%x-W%v') cohort
              FROM nx_notification n
              JOIN nx_user u ON u.id=n.user_id AND u.is_deleted=0
             WHERE n.user_id=#{userId} AND n.is_deleted=0 AND n.read_flag=0
               AND n.push_status IN ('DELIVERED','SENT','SUCCESS')
             ORDER BY n.id FOR UPDATE
            """)
    List<NotificationEventFact> lockUnreadNotificationEventFacts(@Param("userId") Long userId);

    @Select("""
            SELECT user_id userId, notification_id notificationId, action, route,
                   idempotency_key idempotencyKey
             FROM nx_notification_action_receipt
             WHERE idempotency_key=#{idempotencyKey} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    NotificationActionReceipt findNotificationActionReceipt(@Param("idempotencyKey") String idempotencyKey);

    @Insert("""
            INSERT IGNORE INTO nx_notification_action_receipt
              (notification_id,user_id,action,route,idempotency_key,created_at,is_deleted)
            VALUES
              (#{notificationId},#{userId},#{action},#{route},#{idempotencyKey},NOW(),0)
            """)
    int insertNotificationActionReceipt(
            @Param("userId") Long userId,
            @Param("notificationId") Long notificationId,
            @Param("action") String action,
            @Param("route") String route,
            @Param("idempotencyKey") String idempotencyKey);

    @Update("""
            UPDATE nx_notification
               SET read_flag = 1, read_at = COALESCE(read_at, NOW()), push_status = 'READ', updated_at = NOW()
             WHERE user_id = #{userId} AND is_deleted = 0 AND read_flag = 0
               AND push_status IN ('DELIVERED', 'SENT', 'SUCCESS')
            """)
    int markAllUserNotificationsRead(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_notification
               SET is_deleted = 1, updated_at = NOW()
             WHERE user_id = #{userId} AND is_deleted = 0 AND read_flag = 1
            """)
    int clearReadUserNotifications(@Param("userId") Long userId);

    @Update("""
            <script>
            UPDATE nx_notification n
            JOIN (
                SELECT id FROM (
                    SELECT id,
                           ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY created_at DESC, id DESC) AS rn
                      FROM nx_notification
                     WHERE is_deleted = 0 AND LOWER(priority) = #{priority}
                     <if test='userId != null'>AND user_id = #{userId}</if>
                ) ranked WHERE ranked.rn &gt; #{cap}
            ) overflowed ON overflowed.id = n.id
               SET n.is_deleted = 1, n.updated_at = NOW()
            </script>
            """)
    int pruneNotificationsOverCap(
            @Param("priority") String priority,
            @Param("cap") int cap,
            @Param("userId") Long userId);

    @Update("""
            <script>
            UPDATE nx_notification
               SET is_deleted = 1, updated_at = NOW()
             WHERE is_deleted = 0
               AND LOWER(priority) = 'low'
               AND created_at &lt; #{cutoff}
               <if test='userId != null'>AND user_id = #{userId}</if>
            </script>
            """)
    int expireLowPriorityNotifications(@Param("cutoff") LocalDateTime cutoff, @Param("userId") Long userId);
}
