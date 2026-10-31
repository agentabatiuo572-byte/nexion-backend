package ffdd.opsconsole.content.businessnotification.mapper;

import ffdd.opsconsole.content.businessnotification.BusinessNotificationFact;
import ffdd.opsconsole.device.mapper.DeviceOpsMapper;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface BusinessNotificationMapper {
    record AlertDevice(Long deviceId,Long userId) {}

    @Select("SELECT id FROM nx_business_notification_control WHERE id=1 FOR UPDATE")
    Integer lockControl();

    @Select("SELECT id FROM nx_business_notification_control WHERE id=1")
    Integer control();

    // ponytail: bounded anti-receipt scan retains late commits; partition by source if volume warrants it.
    @Select("""
        SELECT f.factKey,f.userId,f.category,f.messageKey,f.route,NULL occurredAt FROM (
          SELECT CONCAT('EVENT:',e.event_type,':',SHA2(e.aggregate_id,256)) factKey,
            CASE WHEN e.event_type='order.refunded'
              THEN (SELECT o.user_id FROM nx_order o WHERE o.order_no=e.aggregate_id AND o.is_deleted=0 LIMIT 1)
              ELSE CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.user_id')),'null') AS UNSIGNED) END userId,
            'FUNDS_RESULT' category,
            CASE e.event_type WHEN 'wallet.topup_confirmed' THEN 'deposit'
              ELSE 'refund' END messageKey,
            '/pages/me/wallet-bills' route
          FROM nx_event_outbox e JOIN nx_business_notification_control b ON b.id=1
          WHERE e.is_deleted=0 AND e.created_at>=b.baseline_at AND e.event_type IN
            ('wallet.topup_confirmed','order.refunded')
          UNION ALL
          SELECT CONCAT('PURCHASE:',p.notification_key,':',p.outcome),p.user_id,'FUNDS_RESULT',
            CASE p.outcome WHEN 'DELIVERED' THEN 'purchase_delivered' WHEN 'PAYMENT_TIMEOUT' THEN 'purchase_timeout' ELSE 'purchase_review' END,
            CASE WHEN p.order_no<>'' THEN CONCAT('/pages/store/order-detail?id=',p.order_no) ELSE '/pages/store/orders' END
          FROM nx_purchase_payment_result p JOIN nx_business_notification_control b ON b.id=1
          WHERE p.created_at>=b.baseline_at
          UNION ALL
          SELECT CONCAT('DEPOSIT:',d.id),d.user_id,'FUNDS_RESULT','deposit','/pages/me/wallet-bills'
          FROM nx_deposit_order d JOIN nx_business_notification_control b ON b.id=1
          WHERE d.is_deleted=0 AND d.status='CREDITED' AND d.ledger_id IS NOT NULL
            AND d.credited_at>=b.baseline_at AND d.chain_name='CREGIS_USDT_BEP20'
          UNION ALL
          SELECT CONCAT('SECURITY_EVENT:',e.event_type,':',SHA2(COALESCE(
              NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.sessionChainId')),'null'),e.event_id),256)),
            CAST(COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.user_id')),'null'),
              NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.targetUserId')),'null')) AS UNSIGNED),
            'ACCOUNT_SECURITY',CASE WHEN e.event_type='auth.password_reset_completed' THEN 'password' ELSE 'security' END,
            '/pages/me/security'
          FROM nx_event_outbox e JOIN nx_business_notification_control b ON b.id=1
          WHERE e.created_at>=b.baseline_at AND e.is_deleted=0 AND
            e.event_type IN ('auth.refresh_token_reuse_detected','auth.login_locked','auth.password_reset_completed')
          UNION ALL
          SELECT CONCAT('AUDIT:',a.id),a.user_id,'ACCOUNT_SECURITY',
            CASE WHEN a.action='USER_PASSWORD_CHANGED' THEN 'password'
              WHEN a.action IN ('PAYOUT_ADDRESS_CREATED','PAYOUT_ADDRESS_CHANGED','USER_PAYMENT_METHOD_UNBIND','USER_PAYMENT_METHOD_BOUND','USER_PAYMENT_METHOD_DEFAULT_CHANGED') THEN 'payment_method'
              ELSE 'security' END,'/pages/me/security'
          FROM nx_audit_log a JOIN nx_business_notification_control b ON b.id=1
          WHERE a.created_at>=b.baseline_at AND a.is_deleted=0 AND a.result='SUCCESS'
            AND a.user_id>0 AND a.action IN ('USER_PASSWORD_CHANGED',
              'USER_TWO_FACTOR_ENABLED','USER_TWO_FACTOR_DISABLED','USER_PHONE_BOUND',
              'PAYOUT_ADDRESS_CREATED','PAYOUT_ADDRESS_CHANGED','USER_PAYMENT_METHOD_UNBIND',
              'USER_PAYMENT_METHOD_BOUND','USER_PAYMENT_METHOD_DEFAULT_CHANGED',
              'C2_USER_STATUS_CHANGED','C2_USER_STATUS_CHANGED_BY_K1','C2_USER_STATUS_RESTORED_BY_K1')
            AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.detail_json,'$.sourceEnvironment')),'PRODUCTION')='PRODUCTION'
            AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.detail_json,'$.sandbox')),'false') NOT IN ('true','1')
          UNION ALL
          SELECT CONCAT('SESSION:',s.session_chain_id),s.user_id,'ACCOUNT_SECURITY','new_signin','/pages/me/security'
          FROM nx_user_session s JOIN nx_business_notification_control b ON b.id=1
          WHERE s.created_at>=b.baseline_at AND s.is_deleted=0 AND s.session_chain_id IS NOT NULL
            AND s.client_ip IS NOT NULL AND s.client_ip<>''
            AND CASE WHEN s.session_group='APP' AND NULLIF(TRIM(s.installation_id),'') IS NOT NULL THEN
              NOT EXISTS(SELECT 1 FROM nx_user_session p WHERE p.user_id=s.user_id
                AND p.client_ip=s.client_ip AND p.session_group=s.session_group AND p.is_deleted=0
                AND p.installation_id=s.installation_id
                AND (p.created_at<s.created_at OR (p.created_at=s.created_at AND p.id<s.id)))
              ELSE NOT EXISTS(SELECT 1 FROM nx_user_session p WHERE p.user_id=s.user_id
                AND p.client_ip=s.client_ip AND p.session_group=s.session_group AND p.is_deleted=0
                AND (p.created_at<s.created_at OR (p.created_at=s.created_at AND p.id<s.id))) END
            AND NOT EXISTS(SELECT 1 FROM nx_user_session p WHERE p.session_chain_id=s.session_chain_id AND p.id<s.id)
          UNION ALL
          SELECT CONCAT('SUPPORT:',m.id),c.user_id,'SUPPORT_REPLY','support',
            CONCAT('/pages/support/chat?cid=',c.conversation_no)
          FROM nx_support_human_message h JOIN nx_conversation_message m ON m.id=h.message_id
            JOIN nx_conversation c ON c.id=m.conversation_id AND c.conversation_no=m.conversation_no
            JOIN nx_business_notification_control b ON b.id=1
          WHERE h.committed_at>=b.baseline_utc_at AND h.actor_type='ADMIN' AND m.sender_type='agent'
            AND m.sender_id=h.actor_id AND h.customer_id=c.user_id
            AND c.is_deleted=0 AND m.is_deleted=0 AND c.conversation_type IN ('support','advisor')
        ) f JOIN nx_user u ON u.id=f.userId AND u.is_deleted=0
        LEFT JOIN nx_business_notification_fact r ON r.fact_key=f.factKey
        WHERE r.fact_key IS NULL ORDER BY f.factKey LIMIT 100
        """)
    List<BusinessNotificationFact> facts();

    String WITHDRAWAL_EVENT_KEY = "CONCAT('EVENT:',e.event_type,':',SHA2(CASE WHEN e.event_type IN "
        + "('withdraw.confirmed','withdraw.refunded') THEN e.aggregate_id ELSE CONCAT(e.aggregate_id,':',e.event_id) END,256))";
    String WITHDRAWAL_EVENTS = "('withdraw.confirmed','withdraw.refunded','withdraw.failed','withdraw.rejected',"
        + "'withdraw.frozen','withdraw.delayed','withdraw.review_due','withdraw.submitted')";
    String WITHDRAWAL_ATTENTION_MATCH = "((UPPER(w.status) IN ('FAILED','TX_FAILED','TX_ORPHANED','DEAD','ADDRESS_INVALID') "
        + "AND e.event_type IN ('withdraw.failed','withdraw.refunded')) OR "
        + "(UPPER(w.status) IN ('REVIEW_REJECTED','REJECTED','REFUNDED') AND e.event_type IN ('withdraw.rejected','withdraw.refunded')) OR "
        + "(UPPER(w.status)='FROZEN' AND (e.event_type='withdraw.frozen' OR (e.event_type='withdraw.submitted' "
        + "AND JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.risk_route'))='freeze'))) OR "
        + "(UPPER(w.status) IN ('EXTENDED_HOLD','DELAYED') AND (e.event_type='withdraw.delayed' OR (e.event_type='withdraw.submitted' "
        + "AND JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.risk_route'))='delay'))) OR "
        + "(UPPER(w.status) IN ('REVIEW_PENDING','REVIEWING') AND (e.event_type='withdraw.review_due' OR (e.event_type='withdraw.submitted' "
        + "AND JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.risk_route')) IN ('manual','high-manual','escalated-manual','strong-review')))))";

    @Select("""
        SELECT
        """ + WITHDRAWAL_EVENT_KEY + """
          factKey,w.user_id userId,'FUNDS_RESULT' category,
          CASE e.event_type WHEN 'withdraw.confirmed' THEN 'withdrawal_completed'
            WHEN 'withdraw.refunded' THEN 'withdrawal_returned' WHEN 'withdraw.failed' THEN 'withdrawal_failed'
            WHEN 'withdraw.rejected' THEN 'withdrawal_rejected' WHEN 'withdraw.frozen' THEN 'withdrawal_frozen'
            WHEN 'withdraw.delayed' THEN 'withdrawal_delayed'
            WHEN 'withdraw.submitted' THEN CASE JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.risk_route'))
              WHEN 'freeze' THEN 'withdrawal_frozen' WHEN 'delay' THEN 'withdrawal_delayed'
              ELSE 'withdrawal_review_required' END
            ELSE 'withdrawal_review_required' END messageKey,
          CONCAT('/pages/me/wallet-withdraw-tracking?id=',w.withdrawal_no) route,
          COALESCE(CASE WHEN JSON_TYPE(JSON_EXTRACT(e.payload,'$.ts')) IN ('INTEGER','UNSIGNED INTEGER')
            AND JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.ts')) REGEXP '^[0-9]{13}$'
            THEN TIMESTAMPADD(SECOND,CAST(JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.ts')) AS UNSIGNED) DIV 1000,
              '1970-01-01 08:00:00') END,e.created_at) occurredAt
        FROM nx_event_outbox e JOIN nx_withdrawal_order w ON w.withdrawal_no=e.aggregate_id AND w.is_deleted=0
        JOIN nx_user u ON u.id=w.user_id AND u.is_deleted=0
        JOIN nx_business_notification_control b ON b.id=1
        LEFT JOIN nx_business_notification_fact r ON r.fact_key=
        """ + WITHDRAWAL_EVENT_KEY + """
        WHERE r.fact_key IS NULL AND e.is_deleted=0 AND e.aggregate_type='WITHDRAWAL'
          AND w.withdrawal_no REGEXP '^WD-[A-Z0-9]{1,93}$'
          AND ((#{historical}=0 AND e.created_at>=b.baseline_at) OR
            (#{historical}=1 AND w.user_id=#{userId} AND e.created_at<b.baseline_at AND
        """ + WITHDRAWAL_ATTENTION_MATCH + """
            AND e.id=(SELECT e.id FROM nx_event_outbox e WHERE e.aggregate_type='WITHDRAWAL'
              AND e.aggregate_id=w.withdrawal_no AND e.is_deleted=0 AND e.created_at<b.baseline_at AND
        """ + WITHDRAWAL_ATTENTION_MATCH + """
              ORDER BY e.created_at DESC,e.id DESC LIMIT 1)))
          AND (e.event_type<>'withdraw.submitted' OR JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.risk_route'))
            IN ('freeze','delay','manual','high-manual','escalated-manual','strong-review'))
          AND e.event_type IN
        """ + WITHDRAWAL_EVENTS + " ORDER BY e.created_at,e.id LIMIT 100")
    List<BusinessNotificationFact> withdrawalFacts(@Param("userId") Long userId,@Param("historical") boolean historical);

    @Select("""
        SELECT CONCAT('STATE:WITHDRAWAL:',SHA2(w.withdrawal_no,256),':',UPPER(w.status)) factKey,
          w.user_id userId,'FUNDS_RESULT' category,'withdrawal_state' messageKey,
          CONCAT('/pages/me/wallet-withdraw-tracking?id=',w.withdrawal_no) route,
          COALESCE(w.failed_at,w.updated_at,w.created_at) occurredAt
        FROM nx_withdrawal_order w JOIN nx_user u ON u.id=w.user_id AND u.is_deleted=0
        JOIN nx_business_notification_control b ON b.id=1
        LEFT JOIN nx_business_notification_fact r ON r.fact_key=CONCAT('STATE:WITHDRAWAL:',SHA2(w.withdrawal_no,256),':',UPPER(w.status))
        WHERE w.user_id=#{userId} AND w.is_deleted=0 AND r.fact_key IS NULL
          AND w.withdrawal_no REGEXP '^WD-[A-Z0-9]{1,93}$'
          AND (UPPER(w.status) IN ('FAILED','TX_FAILED','TX_ORPHANED','DEAD','ADDRESS_INVALID','REVIEW_REJECTED','REJECTED','REFUNDED','FROZEN',
              'REVIEW_PENDING','REVIEWING','DELAYED') OR (UPPER(w.status)='EXTENDED_HOLD' AND LOWER(w.d2_k3_risk_route)='delay'))
          AND COALESCE(w.failed_at,w.updated_at,w.created_at) IS NOT NULL
          AND NOT EXISTS(SELECT 1 FROM nx_event_outbox e WHERE e.aggregate_id=w.withdrawal_no
            AND e.aggregate_type='WITHDRAWAL' AND e.is_deleted=0 AND
        """ + WITHDRAWAL_ATTENTION_MATCH + ") ORDER BY w.created_at,w.withdrawal_no LIMIT 100")
    List<BusinessNotificationFact> withdrawalStateFacts(@Param("userId") Long userId);

    // Repair already-receipted station messages without recreating them or resetting read/event times.
    record WithdrawalRepair(long notificationId,long userId,String route) {}
    @Select("""
        SELECT n.id notificationId,n.user_id userId,CONCAT('/pages/me/wallet-withdraw-tracking?id=',w.withdrawal_no) route
        FROM nx_notification n JOIN nx_business_notification_fact f ON f.notification_biz_no=n.biz_no AND f.user_id=n.user_id
        JOIN nx_event_outbox e ON f.fact_key=
        """ + WITHDRAWAL_EVENT_KEY + """
        JOIN nx_withdrawal_order w ON w.withdrawal_no=e.aggregate_id AND w.user_id=n.user_id AND w.is_deleted=0
        WHERE n.is_deleted=0 AND e.is_deleted=0 AND e.aggregate_type='WITHDRAWAL' AND e.event_type IN
        """ + WITHDRAWAL_EVENTS + """
          AND w.withdrawal_no REGEXP '^WD-[A-Z0-9]{1,93}$'
          AND (#{userId} IS NULL OR n.user_id=#{userId})
          AND (n.cta_href='/pages/me/wallet-bills' OR n.push_status='QUEUED') ORDER BY n.id LIMIT 100
        """)
    List<WithdrawalRepair> withdrawalRepairs(@Param("userId") Long userId);

    @Update("UPDATE nx_notification SET cta_href=#{repair.route},push_status=CASE WHEN push_status='QUEUED' THEN 'DELIVERED' ELSE push_status END "
        + "WHERE id=#{repair.notificationId} AND user_id=#{repair.userId} AND is_deleted=0")
    int repairWithdrawalNotification(@Param("repair") WithdrawalRepair repair);

    @Update("UPDATE nx_notification_action_receipt SET route=#{repair.route} WHERE notification_id=#{repair.notificationId} "
        + "AND user_id=#{repair.userId} AND is_deleted=0 AND route='/pages/me/wallet-bills'")
    int repairWithdrawalActionRoute(@Param("repair") WithdrawalRepair repair);

    String ELIGIBLE = " d.is_deleted=0 AND d.source_environment='PRODUCTION' AND d.run_id='' AND "
        + DeviceOpsMapper.E5_ACTIVATED_OWNED;
    @Insert("INSERT IGNORE INTO nx_business_device_alert_state(device_id,user_id) "
        + "SELECT d.id,d.user_id FROM nx_user_device d WHERE " + ELIGIBLE)
    int seedDevices();

    @Select("SELECT s.device_id FROM nx_business_device_alert_state s LEFT JOIN nx_user_device d ON d.id=s.device_id "
        + "LEFT JOIN nx_user_device_runtime r ON r.user_device_id=d.id AND r.is_deleted=0 "
        + "WHERE s.abnormal_since IS NOT NULL AND NOT COALESCE(("
        + ELIGIBLE + " AND s.user_id=d.user_id AND " + DeviceOpsMapper.E5_RUNTIME_ABNORMAL + "),0) ORDER BY s.device_id")
    List<Long> recoveredDeviceIds();

    @Update("UPDATE nx_business_device_alert_state SET abnormal_since=NULL,alerted=0 WHERE device_id=#{id} AND abnormal_since IS NOT NULL")
    int resetRecoveredDevice(@Param("id") Long id);

    @Select("SELECT s.device_id deviceId,d.user_id userId FROM nx_business_device_alert_state s JOIN nx_user_device d ON d.id=s.device_id "
        + "LEFT JOIN nx_user_device_runtime r ON r.user_device_id=d.id AND r.is_deleted=0 "
        + "WHERE s.abnormal_since IS NULL AND " + ELIGIBLE + " AND " + DeviceOpsMapper.E5_RUNTIME_ABNORMAL
        + " ORDER BY s.device_id")
    List<AlertDevice> abnormalDevices();

    @Update("UPDATE nx_business_device_alert_state SET abnormal_since=NOW(6),user_id=#{user},cycle=cycle+1,alerted=0 "
        + "WHERE device_id=#{id} AND abnormal_since IS NULL")
    int startAbnormalDevice(@Param("id") Long id,@Param("user") Long user);

    @Select("""
        SELECT CONCAT('DEVICE:',s.device_id,':',s.cycle) factKey,s.user_id userId,
          'DEVICE_ALERT' category,'device_alert' messageKey,
          CONCAT('/pages/earn/device-detail?id=',s.device_id) route,NULL occurredAt
        FROM nx_business_device_alert_state s JOIN nx_user_device d ON d.id=s.device_id
        LEFT JOIN nx_user_device_runtime r ON r.user_device_id=d.id AND r.is_deleted=0
        WHERE s.alerted=0 AND s.abnormal_since<=DATE_SUB(NOW(6),INTERVAL 15 MINUTE) AND
        """ + ELIGIBLE + " AND s.user_id=d.user_id AND " + DeviceOpsMapper.E5_RUNTIME_ABNORMAL
        + " ORDER BY s.device_id LIMIT 100")
    List<BusinessNotificationFact> deviceAlerts();

    @Update("UPDATE nx_business_device_alert_state SET alerted=1 WHERE CONCAT('DEVICE:',device_id,':',cycle)=#{factKey}")
    int markDeviceAlert(String factKey);

    @Select("""
        SELECT CONCAT('TRIAL:',q.id,':',q.deadlineKind,':',DATE_FORMAT(CASE WHEN q.dueAt<=NOW() THEN q.claimed_at ELSE q.dueAt END,'%Y%m%d%H%i%s'),':',
          CASE WHEN q.dueAt<=NOW() THEN 'end' WHEN q.dueAt<=DATE_ADD(NOW(),INTERVAL 1 HOUR) THEN '1h' ELSE '24h' END) factKey,
          q.user_id userId,'DEVICE_EXPIRY' category,
          CONCAT(q.deadlineKind,'_',CASE WHEN q.dueAt<=NOW() THEN 'end'
            WHEN q.dueAt<=DATE_ADD(NOW(),INTERVAL 1 HOUR) THEN '1h' ELSE '24h' END) messageKey,
          '/pages/me/trial' route,NULL occurredAt
        FROM (
          SELECT t.id,t.user_id,t.claimed_at,t.expires_at dueAt,'trial' deadlineKind
          FROM nx_trial_claim t WHERE t.is_deleted=0 AND t.status IN ('CLAIMED','ACTIVE','GRACE')
          UNION ALL
          SELECT t.id,t.user_id,t.claimed_at,CASE WHEN t.extended_at IS NOT NULL THEN t.expires_at
            ELSE TIMESTAMPADD(DAY,COALESCE((SELECT CAST(current_value AS UNSIGNED)
              FROM nx_growth_trial_policy WHERE policy_key='graceDays' AND is_deleted=0 LIMIT 1),7),t.expires_at) END,
            'retention' FROM nx_trial_claim t WHERE t.is_deleted=0 AND t.status IN ('CLAIMED','ACTIVE','GRACE','EXTENDED')
          UNION ALL
          SELECT t.id,t.user_id,t.claimed_at,t.closed_at,'retention'
          FROM nx_trial_claim t WHERE t.is_deleted=0 AND t.status='CANCELLED'
            AND t.cancel_reason IN ('auto_end','auto_settlement_window_expired') AND t.closed_at<=NOW()
        ) q JOIN nx_business_notification_control b ON b.id=1
        JOIN nx_user u ON u.id=q.user_id AND u.is_deleted=0
        LEFT JOIN nx_business_notification_fact r ON r.fact_key=CONCAT('TRIAL:',q.id,':',q.deadlineKind,':',
          DATE_FORMAT(CASE WHEN q.dueAt<=NOW() THEN q.claimed_at ELSE q.dueAt END,'%Y%m%d%H%i%s'),':',CASE WHEN q.dueAt<=NOW() THEN 'end'
            WHEN q.dueAt<=DATE_ADD(NOW(),INTERVAL 1 HOUR) THEN '1h' ELSE '24h' END)
        WHERE r.fact_key IS NULL AND q.dueAt>=b.baseline_at AND q.dueAt<=DATE_ADD(NOW(),INTERVAL 24 HOUR)
          AND (q.dueAt<=NOW() OR (q.dueAt<=DATE_ADD(NOW(),INTERVAL 1 HOUR)
            AND DATE_SUB(q.dueAt,INTERVAL 1 HOUR)>=GREATEST(b.baseline_at,q.claimed_at))
            OR (q.dueAt>DATE_ADD(NOW(),INTERVAL 1 HOUR)
            AND DATE_SUB(q.dueAt,INTERVAL 24 HOUR)>=GREATEST(b.baseline_at,q.claimed_at)))
        ORDER BY q.dueAt,q.id LIMIT 100
        """)
    List<BusinessNotificationFact> trialDeadlines();

    @Insert("INSERT IGNORE INTO nx_business_notification_fact(fact_key,notification_biz_no,user_id) VALUES(#{factKey},#{bizNo},#{userId})")
    int receipt(@Param("factKey") String factKey,@Param("bizNo") String bizNo,@Param("userId") Long userId);

    @Select("""
        SELECT n.biz_no FROM nx_event_outbox e JOIN nx_notification n
          ON n.biz_no=CONCAT('HDPAY:',e.aggregate_id) AND n.user_id=#{userId} AND n.is_deleted=0
        WHERE e.event_type='wallet.topup_confirmed' AND JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.psp'))='HDPAY'
          AND CONCAT('EVENT:',e.event_type,':',SHA2(e.aggregate_id,256))=#{factKey} LIMIT 1
        """)
    String existingTopup(@Param("factKey") String factKey,@Param("userId") Long userId);

    @Select("""
        SELECT n.biz_no FROM nx_audit_log a JOIN nx_notification n
          ON n.biz_no=CONCAT('PAYMENT_METHOD_UNBOUND:',a.resource_id,':',JSON_UNQUOTE(JSON_EXTRACT(a.detail_json,'$.idempotencyKey')))
          AND n.user_id=#{userId} AND n.is_deleted=0
        WHERE a.action='USER_PAYMENT_METHOD_UNBIND' AND CONCAT('AUDIT:',a.id)=#{factKey} LIMIT 1
        """)
    String existingPaymentMethod(@Param("factKey") String factKey,@Param("userId") Long userId);

    @Insert("""
        INSERT INTO nx_notification(biz_no,user_id,type,priority,title,body,cta_label,cta_href,
          read_flag,push_status,push_attempts,next_push_at,created_at,updated_at,is_deleted)
        SELECT #{bizNo},u.id,#{fact.category},CASE WHEN #{fact.category} IN ('FUNDS_RESULT','ACCOUNT_SECURITY') THEN 'critical' ELSE 'high' END,
          CASE WHEN LOWER(u.language) LIKE 'zh%' THEN #{zh} WHEN LOWER(u.language) LIKE 'vi%' THEN #{vi} ELSE #{en} END,
          CASE WHEN LOWER(u.language) LIKE 'zh%' THEN #{zh} WHEN LOWER(u.language) LIKE 'vi%' THEN #{vi} ELSE #{en} END,
          CASE WHEN LOWER(u.language) LIKE 'zh%' THEN '查看详情' WHEN LOWER(u.language) LIKE 'vi%' THEN 'Xem chi tiết' ELSE 'View details' END,
          #{fact.route},0,CASE WHEN #{fact.messageKey} LIKE 'withdrawal_%' THEN 'DELIVERED' ELSE 'QUEUED' END,
          0,NOW(),COALESCE(#{fact.occurredAt},NOW()),NOW(),0
        FROM nx_user u WHERE u.id=#{fact.userId} AND u.is_deleted=0
        ON DUPLICATE KEY UPDATE type=VALUES(type),priority=VALUES(priority),title=VALUES(title),body=VALUES(body),
          cta_label=VALUES(cta_label),cta_href=VALUES(cta_href)
        """)
    int notify(@Param("fact") BusinessNotificationFact fact,@Param("bizNo") String bizNo,
        @Param("zh") String zh,@Param("en") String en,@Param("vi") String vi);
}
