package ffdd.opsconsole.content.push.mapper;

import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** B79-BE-04 generation state and native-only delivery projections. */
@Mapper
public interface EarningPushMapper {
    record Preference(String earningPush, Boolean system) { }
    record Installation(String timeZone, String capabilities) { }
    record Receipt(String receiptNo, LocalDateTime postedAt, BigDecimal usdt, BigDecimal uvt,
                   String deviceName, String taskClass, Long deviceId) {
        public Receipt(String receiptNo, LocalDateTime postedAt, BigDecimal usdt, BigDecimal uvt,
                       String deviceName, String taskClass) {
            this(receiptNo, postedAt, usdt, uvt, deviceName, taskClass, null);
        }
    }
    record FirstReceipt(String receiptNo, LocalDateTime postedAt) { }
    record UserSchedule(long userId, String language, String earningPush, Boolean system, String timeZone, Boolean eligible) { }
    record Task(long id, String status, String reason, Long notificationId, LocalDateTime expiresAt) { }

    @Select("SELECT earning_push AS earningPush,notify_system AS `system` FROM nx_user_preference WHERE user_id=#{userId} AND is_deleted=0 LIMIT 1")
    Preference preference(@Param("userId") long userId);

    @Select("SELECT language FROM nx_user WHERE id=#{userId} AND is_deleted=0 LIMIT 1")
    String language(@Param("userId") long userId);

    @Select("""
        SELECT i.time_zone AS timeZone,i.capabilities FROM nx_native_push_installation i
         WHERE i.user_id=#{userId} AND i.active=1 AND i.permission='granted'
           AND i.time_zone IS NOT NULL AND JSON_CONTAINS(i.capabilities, '"earning_push_v1"')
           AND EXISTS(SELECT 1 FROM nx_user_session s JOIN nx_app_session_state a ON a.user_id=s.user_id
             AND a.session_chain_id=s.session_chain_id AND a.app_session_version=s.app_session_version
             AND a.installation_id=s.installation_id AND a.execution_supported=s.execution_supported
             WHERE s.user_id=i.user_id AND s.installation_id=i.installation_id AND s.session_chain_id=i.session_chain_id
               AND s.session_group='APP' AND s.is_deleted=0 AND s.revoked_at IS NULL AND s.session_ended_reason IS NULL
               AND s.expires_at>NOW()
               AND COALESCE(s.last_active_at,s.created_at)>DATE_SUB(NOW(),INTERVAL #{idleDays} DAY)
               AND NOT EXISTS(SELECT 1 FROM nx_user_security sec WHERE sec.user_id=i.user_id AND sec.is_deleted=0 AND sec.password_reset_required=1))
         ORDER BY i.updated_at DESC,i.installation_id LIMIT 1
        """)
    Installation installation(@Param("userId") long userId, @Param("idleDays") int idleDays);

    @Select("SELECT cutover_at FROM nx_earning_notification_cutover WHERE id=1")
    LocalDateTime cutover();

    @Select("""
        <script>
        SELECT e.receipt_no receiptNo,
               MIN(CONVERT_TZ(e.wallet_posted_at,#{offset},'+00:00')) postedAt,
               COALESCE(SUM(CASE WHEN e.asset='USDT' AND e.amount&gt;0 THEN e.amount ELSE 0 END),0) usdt,
               COALESCE(SUM(CASE WHEN e.asset='UVT' AND e.amount&gt;0 THEN e.amount ELSE 0 END),0) uvt,
               (SELECT d.name FROM nx_earning_event x JOIN nx_user_device d ON d.id=x.user_device_id
                 WHERE x.receipt_no=e.receipt_no AND x.user_id=e.user_id AND x.is_deleted=0 ORDER BY x.id LIMIT 1) deviceName,
               (
        """ + AppTaskAssignmentMapper.EARNING_TASK_CLASS_BY_RECEIPT + """
                AND r.user_id=e.user_id AND r.receipt_no=e.receipt_no
               ) taskClass,
               (SELECT d.id FROM nx_earning_event x JOIN nx_user_device d ON d.id=x.user_device_id
                 WHERE x.receipt_no=e.receipt_no AND x.user_id=e.user_id AND x.is_deleted=0 ORDER BY x.id LIMIT 1) deviceId
          FROM nx_earning_event e
          JOIN nx_earning_notification_cutover c ON c.id=1
         WHERE e.user_id=#{userId} AND e.receipt_no=#{receiptNo} AND e.is_deleted=0
           AND e.status='POSTED' AND e.amount&gt;0
           AND CONVERT_TZ(e.wallet_posted_at,#{offset},'+00:00')&gt;=c.cutover_at
         GROUP BY e.receipt_no,e.user_id
        </script>
        """)
    Receipt receipt(@Param("userId") long userId, @Param("receiptNo") String receiptNo,
                    @Param("offset") String storageOffset);

    @Select("""
        SELECT COUNT(*) FROM nx_earning_event e
         WHERE e.user_id=#{userId} AND e.is_deleted=0 AND e.status='POSTED' AND e.amount>0
           AND CONVERT_TZ(e.wallet_posted_at,#{offset},'+00:00') < #{cutover}
        """)
    int positiveBeforeCutover(@Param("userId") long userId, @Param("cutover") LocalDateTime cutover,
                              @Param("offset") String storageOffset);

    @Select("""
        SELECT e.receipt_no receiptNo,
               MIN(CONVERT_TZ(e.wallet_posted_at,#{offset},'+00:00')) postedAt
          FROM nx_earning_event e
          JOIN nx_earning_notification_cutover c ON c.id=1
         WHERE e.user_id=#{userId} AND e.is_deleted=0 AND e.status='POSTED' AND e.amount>0
           AND e.receipt_no IS NOT NULL AND e.receipt_no<>''
           AND CONVERT_TZ(e.wallet_posted_at,#{offset},'+00:00')>=c.cutover_at
         GROUP BY e.receipt_no,e.user_id
         ORDER BY postedAt,e.receipt_no LIMIT 1
        """)
    FirstReceipt firstReceipt(@Param("userId") long userId, @Param("offset") String storageOffset);

    @Select("""
        SELECT e.receipt_no receiptNo,
               MIN(CONVERT_TZ(e.wallet_posted_at,#{offset},'+00:00')) postedAt,
               COALESCE(SUM(CASE WHEN e.asset='USDT' AND e.amount>0 THEN e.amount ELSE 0 END),0) usdt,
               COALESCE(SUM(CASE WHEN e.asset='UVT' AND e.amount>0 THEN e.amount ELSE 0 END),0) uvt,
               (SELECT d.name FROM nx_earning_event x JOIN nx_user_device d ON d.id=x.user_device_id
                 WHERE x.receipt_no=e.receipt_no AND x.user_id=e.user_id AND x.is_deleted=0 ORDER BY x.id LIMIT 1) deviceName,
               (
        """ + AppTaskAssignmentMapper.EARNING_TASK_CLASS_BY_RECEIPT + """
                AND r.user_id=e.user_id AND r.receipt_no=e.receipt_no
               ) taskClass,
               (SELECT d.id FROM nx_earning_event x JOIN nx_user_device d ON d.id=x.user_device_id
                 WHERE x.receipt_no=e.receipt_no AND x.user_id=e.user_id AND x.is_deleted=0 ORDER BY x.id LIMIT 1) deviceId
          FROM nx_visible_earning_event e
          JOIN nx_earning_notification_cutover c ON c.id=1
         WHERE e.user_id=#{userId} AND e.is_deleted=0 AND e.status='POSTED' AND e.amount>0
           AND e.receipt_no IS NOT NULL AND e.receipt_no<>''
           AND CONVERT_TZ(e.wallet_posted_at,#{offset},'+00:00')>=c.cutover_at
           AND CONVERT_TZ(e.wallet_posted_at,#{offset},'+00:00')>=#{from}
           AND CONVERT_TZ(e.wallet_posted_at,#{offset},'+00:00')<#{to}
         GROUP BY e.receipt_no,e.user_id
         ORDER BY postedAt,e.receipt_no
        """)
    List<Receipt> receiptsBetween(@Param("userId") long userId, @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to, @Param("offset") String storageOffset);

    @Select("""
        SELECT u.id userId,u.language,
               CASE WHEN p.earning_push IN ('REALTIME','DAILY','HALF_HOURLY','OFF') THEN p.earning_push ELSE 'DAILY' END earningPush,
               COALESCE(p.notify_system,1) `system`,
               COALESCE(
                 (SELECT i.time_zone FROM nx_native_push_installation i
                   WHERE i.user_id=u.id AND i.active=1 AND i.permission='granted'
                     AND i.time_zone IS NOT NULL AND JSON_CONTAINS(i.capabilities, '"earning_push_v1"')
                     AND EXISTS(SELECT 1 FROM nx_user_session s JOIN nx_app_session_state a ON a.user_id=s.user_id
                       AND a.session_chain_id=s.session_chain_id AND a.app_session_version=s.app_session_version
                       AND a.installation_id=s.installation_id AND a.execution_supported=s.execution_supported
                       WHERE s.user_id=i.user_id AND s.installation_id=i.installation_id AND s.session_chain_id=i.session_chain_id
                         AND s.session_group='APP' AND s.is_deleted=0 AND s.revoked_at IS NULL AND s.session_ended_reason IS NULL
                         AND s.expires_at>NOW()
                         AND COALESCE(s.last_active_at,s.created_at)>DATE_SUB(NOW(),INTERVAL #{idleDays} DAY))
                     AND NOT EXISTS(SELECT 1 FROM nx_user_security sec WHERE sec.user_id=i.user_id AND sec.is_deleted=0 AND sec.password_reset_required=1)
                   ORDER BY i.updated_at DESC,i.installation_id LIMIT 1),
                 (SELECT i.time_zone FROM nx_native_push_installation i
                   WHERE i.user_id=u.id AND i.time_zone IS NOT NULL
                   ORDER BY i.updated_at DESC,i.installation_id LIMIT 1)) timeZone,
               EXISTS(SELECT 1 FROM nx_native_push_installation i
                 WHERE i.user_id=u.id AND i.active=1 AND i.permission='granted'
                   AND i.time_zone IS NOT NULL AND JSON_CONTAINS(i.capabilities, '"earning_push_v1"')
                   AND EXISTS(SELECT 1 FROM nx_user_session s JOIN nx_app_session_state a ON a.user_id=s.user_id
                     AND a.session_chain_id=s.session_chain_id AND a.app_session_version=s.app_session_version
                     AND a.installation_id=s.installation_id AND a.execution_supported=s.execution_supported
                     WHERE s.user_id=i.user_id AND s.installation_id=i.installation_id AND s.session_chain_id=i.session_chain_id
                       AND s.session_group='APP' AND s.is_deleted=0 AND s.revoked_at IS NULL AND s.session_ended_reason IS NULL
                       AND s.expires_at>NOW()
                       AND COALESCE(s.last_active_at,s.created_at)>DATE_SUB(NOW(),INTERVAL #{idleDays} DAY))
                   AND NOT EXISTS(SELECT 1 FROM nx_user_security sec WHERE sec.user_id=i.user_id AND sec.is_deleted=0 AND sec.password_reset_required=1)) eligible
          FROM nx_user u LEFT JOIN nx_user_preference p ON p.user_id=u.id AND p.is_deleted=0
         WHERE u.is_deleted=0 AND u.status='ACTIVE'
           AND EXISTS(SELECT 1 FROM nx_native_push_installation i WHERE i.user_id=u.id AND i.time_zone IS NOT NULL)
        """)
    List<UserSchedule> schedules(@Param("idleDays") int idleDays);

    @Select("SELECT id,status,reason,notification_id notificationId,expires_at expiresAt FROM nx_earning_push_task WHERE task_key=#{key}")
    Task task(@Param("key") String key);

    @Insert("""
        INSERT IGNORE INTO nx_earning_push_task
          (task_key,user_id,kind,status,reason,receipt_no,window_start,window_end,business_zone,
           event_count,device_count,usdt,uvt,title,body,cta_href,expires_at,created_at,updated_at)
        VALUES (#{key},#{userId},#{kind},'PENDING',#{reason},#{receiptNo},#{windowStart},#{windowEnd},#{zone},
           #{eventCount},#{deviceCount},#{usdt},#{uvt},NULL,NULL,NULL,#{expiresAt},NOW(6),NOW(6))
        """)
    int insertTask(@Param("key") String key,@Param("userId") long userId,@Param("kind") String kind,
                   @Param("reason") String reason,@Param("receiptNo") String receiptNo,
                   @Param("windowStart") LocalDateTime windowStart,@Param("windowEnd") LocalDateTime windowEnd,
                   @Param("zone") String zone,@Param("eventCount") int eventCount,@Param("deviceCount") int deviceCount,
                   @Param("usdt") BigDecimal usdt,@Param("uvt") BigDecimal uvt,@Param("expiresAt") LocalDateTime expiresAt);

    @Update("""
        UPDATE nx_earning_push_task SET status=#{status},reason=#{reason},title=#{title},body=#{body},cta_href=#{ctaHref},
          notification_id=#{notificationId},updated_at=NOW(6) WHERE id=#{id}
        """)
    int completeTask(@Param("id") long id,@Param("status") String status,@Param("reason") String reason,
                     @Param("title") String title,@Param("body") String body,@Param("ctaHref") String ctaHref,
                     @Param("notificationId") Long notificationId);

    @Update("""
        UPDATE nx_earning_push_task SET receipt_no=#{receiptNo},event_count=#{eventCount},device_count=#{deviceCount},
          usdt=#{usdt},uvt=#{uvt},updated_at=NOW(6) WHERE id=#{id}
        """)
    int freezeTask(@Param("id") long id,@Param("receiptNo") String receiptNo,
                   @Param("eventCount") int eventCount,@Param("deviceCount") int deviceCount,
                   @Param("usdt") BigDecimal usdt,@Param("uvt") BigDecimal uvt);

    @Insert("""
        INSERT INTO nx_notification(biz_no,user_id,type,priority,title,body,cta_href,push_status,created_at,updated_at,is_deleted)
        VALUES(#{bizNo},#{userId},#{type},'low',#{title},#{body},#{ctaHref},'NATIVE_ONLY',NOW(6),NOW(6),0)
        """)
    int insertNotification(@Param("bizNo") String bizNo,@Param("userId") long userId,@Param("type") String type,
                           @Param("title") String title,@Param("body") String body,@Param("ctaHref") String ctaHref);

    @Select("SELECT id FROM nx_notification WHERE biz_no=#{bizNo} AND user_id=#{userId} AND is_deleted=0 LIMIT 1")
    Long notificationId(@Param("bizNo") String bizNo,@Param("userId") long userId);

    @Insert("""
        INSERT IGNORE INTO nx_native_push_delivery
          (notification_id,installation_id,user_id,binding_epoch,status,attempts,next_attempt_at,created_at,updated_at,expires_at,thread_id)
        SELECT #{notificationId},i.installation_id,i.user_id,i.binding_epoch,'QUEUED',0,NOW(6),NOW(6),NOW(6),#{expiresAt},#{threadId}
          FROM nx_native_push_installation i
         WHERE i.user_id=#{userId} AND i.active=1 AND i.permission='granted'
           AND i.time_zone IS NOT NULL AND JSON_CONTAINS(i.capabilities, '"earning_push_v1"')
           AND EXISTS(SELECT 1 FROM nx_user_session s JOIN nx_app_session_state a ON a.user_id=s.user_id
             AND a.session_chain_id=s.session_chain_id AND a.app_session_version=s.app_session_version
             AND a.installation_id=s.installation_id AND a.execution_supported=s.execution_supported
             WHERE s.user_id=i.user_id AND s.installation_id=i.installation_id AND s.session_chain_id=i.session_chain_id
               AND s.session_group='APP' AND s.is_deleted=0 AND s.revoked_at IS NULL AND s.session_ended_reason IS NULL
               AND s.expires_at>NOW()
               AND COALESCE(s.last_active_at,s.created_at)>DATE_SUB(NOW(),INTERVAL #{idleDays} DAY)
               AND NOT EXISTS(SELECT 1 FROM nx_user_security sec WHERE sec.user_id=i.user_id AND sec.is_deleted=0 AND sec.password_reset_required=1))
        """)
    int queueDeliveries(@Param("notificationId") long notificationId,@Param("userId") long userId,
                        @Param("expiresAt") LocalDateTime expiresAt,@Param("threadId") String threadId,
                        @Param("idleDays") int idleDays);

    @Select("""
        SELECT COUNT(*) FROM nx_earning_push_task t
         WHERE t.user_id=#{userId} AND t.kind IN ('FIRST','REALTIME') AND t.status='SENT'
           AND t.receipt_no=#{receiptNo} AND t.window_start<#{to} AND t.window_end>#{from}
        """)
    int sentSingleInWindow(@Param("userId") long userId,@Param("receiptNo") String receiptNo,
                           @Param("from") LocalDateTime from,@Param("to") LocalDateTime to);
}
