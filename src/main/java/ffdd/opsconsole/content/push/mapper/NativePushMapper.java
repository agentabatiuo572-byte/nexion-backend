package ffdd.opsconsole.content.push.mapper;

import org.apache.ibatis.annotations.*;

// Multi-table transport projections; no single mutable CRUD aggregate is exposed by this mapper.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface NativePushMapper {
    record Installation(String installationId, String clientId, String provider, String bindingTokenHash, Long userId,
                        String sessionChainId, String platform, String permission, String appVersion,
                        long bindingEpoch, boolean active, String apnsTopic, Integer appBuild,
                        String timeZone, String capabilities) {
        @AutomapConstructor public Installation { }
        public Installation(String installationId, String clientId, String provider, String bindingTokenHash, Long userId,
                String sessionChainId, String platform, String permission, String appVersion, long bindingEpoch, boolean active) {
            this(installationId, clientId, provider, bindingTokenHash, userId, sessionChainId, platform, permission,
                    appVersion, bindingEpoch, active, null, null, null, "[]");
        }
        public Installation(String installationId, String clientId, String provider, String bindingTokenHash, Long userId,
                String sessionChainId, String platform, String permission, String appVersion, long bindingEpoch, boolean active,
                String apnsTopic, Integer appBuild) {
            this(installationId, clientId, provider, bindingTokenHash, userId, sessionChainId, platform, permission,
                    appVersion, bindingEpoch, active, apnsTopic, appBuild, null, "[]");
        }
        public boolean earningPushEligible() {
            return active && timeZone != null && capabilities != null
                    && capabilities.contains("\"earning_push_v1\"");
        }
    }
    record Delivery(long id, long notificationId, String installationId, long userId, long bindingEpoch,
                    int attempts, String leaseToken) { }
    record Notification(long id, String type, String ctaHref, String language, String title, String body, Integer updateBuild) {
        @AutomapConstructor public Notification { }
        public Notification(long id, String type, String ctaHref, String language) {
            this(id, type, ctaHref, language, null, null, null);
        }
        public Notification(long id, String type, String ctaHref, String language, String title, String body) {
            this(id, type, ctaHref, language, title, body, null);
        }
        public boolean isNova() { return type.startsWith("NOVA_"); }
    }

    @Select("SELECT installation_id,client_id,provider,binding_token_hash,user_id,session_chain_id,platform,permission,app_version,binding_epoch,active,apns_topic,app_build,time_zone,capabilities FROM nx_native_push_installation WHERE installation_id=#{id} FOR UPDATE")
    Installation lockInstallation(String id);

    @Select("SELECT installation_id FROM nx_native_push_installation WHERE token_digest=SHA2(CONCAT(#{provider},CHAR(0),#{token}),256)")
    String tokenOwner(@Param("provider") String provider, @Param("token") String token);

    @Insert("""
        INSERT INTO nx_native_push_installation
        (installation_id,client_id,provider,token_digest,binding_token_hash,user_id,session_chain_id,platform,permission,app_version,
         binding_epoch,active,bound_at,updated_at,apns_topic,app_build,time_zone,capabilities)
        VALUES(#{installationId},#{clientId},#{provider},SHA2(CONCAT(#{provider},CHAR(0),#{clientId}),256),#{bindingTokenHash},#{userId},#{sessionChainId},#{platform},
         #{permission},#{appVersion},1,1,NOW(6),NOW(6),#{apnsTopic},#{appBuild},#{timeZone},#{capabilities})
        """)
    int insert(Installation installation);

    @Update("""
        UPDATE nx_native_push_installation SET client_id=#{i.clientId},provider=#{i.provider},token_digest=SHA2(CONCAT(#{i.provider},CHAR(0),#{i.clientId}),256),user_id=#{i.userId},session_chain_id=#{i.sessionChainId},
          platform=#{i.platform},permission=#{i.permission},app_version=#{i.appVersion},apns_topic=#{i.apnsTopic},app_build=#{i.appBuild},
          time_zone=#{i.timeZone},capabilities=#{i.capabilities},active=1,
          binding_epoch=binding_epoch+#{changed},
          bound_at=IF(#{changed}=1,NOW(6),bound_at),
          updated_at=NOW(6) WHERE installation_id=#{i.installationId}
        """)
    int update(@Param("i") Installation installation, @Param("changed") int changed);

    @Update("""
        UPDATE nx_native_push_installation SET active=0,binding_epoch=binding_epoch+1,
          updated_at=NOW(6) WHERE installation_id=#{id} AND active=1
        """)
    int deactivate(String id);

    @Update("""
        UPDATE nx_native_push_delivery SET status='CANCELLED',lease_token=NULL,lease_until=NULL,last_error='BINDING_CHANGED',
          updated_at=NOW(6)
        WHERE installation_id=#{id} AND status IN ('QUEUED','RETRY','SENDING','BLOCKED')
        """)
    int cancelPending(String id);

    @Select("SELECT notification_id FROM nx_native_push_cursor WHERE id=1 FOR UPDATE")
    Long lockCursor();

    @Insert("""
        INSERT IGNORE INTO nx_native_push_delivery
        (notification_id,installation_id,user_id,binding_epoch,status,attempts,next_attempt_at,created_at,updated_at)
        SELECT n.id,i.installation_id,n.user_id,i.binding_epoch,'QUEUED',0,
          NOW(6),NOW(6),NOW(6)
        FROM nx_notification n JOIN nx_native_push_installation i ON i.user_id=n.user_id AND i.active=1 AND i.permission='granted' AND ((i.provider='FCM' AND i.platform='android') OR (i.provider='APNS' AND i.platform='ios'))
        JOIN nx_user u ON u.id=n.user_id AND u.is_deleted=0 AND u.status='ACTIVE' AND u.sandbox=#{sandbox}
        LEFT JOIN nx_user_preference p ON p.user_id=n.user_id AND p.is_deleted=0
        WHERE n.id>#{low} AND n.id<=#{high} AND n.is_deleted=0
          AND n.biz_no NOT LIKE 'STATION1:%'
          AND (n.biz_no LIKE 'NATIVE1:%' OR EXISTS(SELECT 1 FROM nx_business_notification_fact f WHERE f.notification_biz_no=n.biz_no AND f.user_id=n.user_id))
          AND n.type IN ('DEVICE_EXPIRY','DEVICE_ALERT','FUNDS_RESULT','ACCOUNT_SECURITY','SUPPORT_REPLY')
          AND (n.created_at>=i.bound_at OR (n.type='ACCOUNT_SECURITY' AND LOWER(n.priority)='critical'))
          AND n.created_at>DATE_SUB(NOW(6),INTERVAL 24 HOUR)
          AND (LOWER(n.priority)='critical' OR COALESCE(p.notify_system,1)=1)
        """)
    int discover(@Param("low") long low, @Param("high") long high, @Param("sandbox") int sandbox);

    String LIVE_NOVA_CHANNEL = """
        EXISTS (SELECT 1 FROM nx_nova_channel c JOIN nx_nova_template t ON t.channel_key=c.channel_key
          WHERE CONCAT('NOVA_',UPPER(c.channel_key))=n.type AND c.is_deleted=0 AND c.enabled=1
            AND t.is_deleted=0 AND t.status='PUBLISHED')
        """;

    @Insert("""
        INSERT IGNORE INTO nx_native_push_delivery
          (notification_id,installation_id,user_id,binding_epoch,status,attempts,next_attempt_at,created_at,updated_at)
        SELECT n.id,i.installation_id,n.user_id,i.binding_epoch,'QUEUED',0,NOW(6),NOW(6),NOW(6)
        FROM nx_native_push_cursor cutover JOIN nx_notification n ON n.id>cutover.notification_id
        JOIN nx_native_push_installation i ON i.user_id=n.user_id AND i.active=1 AND i.permission='granted'
          AND ((i.provider='FCM' AND i.platform='android') OR (i.provider='APNS' AND i.platform='ios' AND i.apns_topic=#{testTopic}))
        JOIN nx_user u ON u.id=n.user_id AND u.is_deleted=0 AND u.status='ACTIVE' AND u.sandbox=#{sandbox}
        LEFT JOIN nx_user_preference p ON p.user_id=u.id AND p.is_deleted=0
        WHERE cutover.id=2 AND n.biz_no LIKE 'NOVA-%' AND n.push_status IN ('DELIVERED','READ') AND n.is_deleted=0
          AND (n.cta_href IS NULL OR BINARY n.cta_href IN ('','NONE','/team','/earn'))
          AND n.created_at>=i.bound_at AND n.created_at>DATE_SUB(NOW(6),INTERVAL 24 HOUR)
          AND COALESCE(p.notify_system,1)=1
          AND EXISTS (SELECT 1 FROM nx_event_outbox e WHERE e.aggregate_type='NOVA_NOTIFICATION'
            AND e.aggregate_id=CAST(n.id AS CHAR) AND e.event_type='nova.push_sent' AND e.is_deleted=0
            AND JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.user_id'))=CAST(n.user_id AS CHAR))
        """ + " AND " + LIVE_NOVA_CHANNEL)
    int discoverNova(@Param("sandbox") int sandbox, @Param("testTopic") String testTopic);

    String IOS_UPDATE_INSTALLATIONS = """
        FROM nx_native_push_installation i
        JOIN nx_user u ON u.id=i.user_id AND u.is_deleted=0 AND u.status='ACTIVE' AND u.sandbox=#{sandbox}
        JOIN nx_app_session_state a ON a.user_id=i.user_id AND a.installation_id=i.installation_id AND a.session_chain_id=i.session_chain_id
        LEFT JOIN nx_user_preference p ON p.user_id=i.user_id AND p.is_deleted=0
        WHERE i.platform='ios' AND i.provider='APNS' AND i.apns_topic=#{testTopic}
          AND i.active=1 AND i.permission='granted' AND i.app_build<#{build} AND COALESCE(p.notify_system,1)=1
          AND EXISTS(SELECT 1 FROM nx_user_session s WHERE s.user_id=i.user_id AND s.installation_id=i.installation_id
              AND s.session_chain_id=i.session_chain_id AND s.app_session_version=a.app_session_version
              AND s.session_group='APP' AND s.is_deleted=0 AND s.revoked_at IS NULL
              AND s.session_ended_reason IS NULL AND s.expires_at>NOW())
        """;

    @Insert("""
        INSERT IGNORE INTO nx_notification(biz_no,user_id,type,title,body,cta_href,push_status)
        SELECT DISTINCT CONCAT('APP_UPDATE:ios:',#{build}),u.id,'APP_UPDATE',
          CASE LOWER(LEFT(u.language,2)) WHEN 'zh' THEN '有新版本' WHEN 'vi' THEN 'Có phiên bản mới' ELSE 'New version available' END,
          CASE LOWER(LEFT(u.language,2))
            WHEN 'zh' THEN CONCAT(#{versionName},' 已发布，点击查看更新。')
            WHEN 'vi' THEN CONCAT('Phiên bản ',#{versionName},' đã phát hành. Nhấn để xem bản cập nhật.')
            ELSE CONCAT('Version ',#{versionName},' is ready. Tap to view the update.')
          END, '/pages/me/app-update', 'NATIVE_ONLY'
        """ + IOS_UPDATE_INSTALLATIONS)
    int createIosUpdateNotices(@Param("build") int build, @Param("versionName") String versionName,
                               @Param("sandbox") int sandbox, @Param("testTopic") String testTopic);

    @Insert("""
        INSERT IGNORE INTO nx_native_push_delivery
          (notification_id,installation_id,user_id,binding_epoch,status,attempts,next_attempt_at,created_at,updated_at)
        SELECT n.id,d.installation_id,d.user_id,d.binding_epoch,'QUEUED',0,NOW(6),NOW(6),NOW(6)
        FROM (
          SELECT i.installation_id,i.user_id,i.binding_epoch,
                 ROW_NUMBER() OVER(PARTITION BY i.user_id ORDER BY i.updated_at DESC,i.installation_id) AS preference
        """ + IOS_UPDATE_INSTALLATIONS + """
        ) d
        JOIN nx_notification n ON n.user_id=d.user_id AND n.biz_no=CONCAT('APP_UPDATE:ios:',#{build})
        LEFT JOIN nx_native_push_delivery prior ON prior.notification_id=n.id
        WHERE d.preference=1 AND n.is_deleted=0 AND prior.id IS NULL
        """)
    int queueIosUpdateNotices(@Param("build") int build, @Param("sandbox") int sandbox, @Param("testTopic") String testTopic);

    @Select("""
        SELECT COUNT(*) FROM nx_app_update_platform s
        JOIN nx_app_update_artifact a ON a.artifact_id=JSON_UNQUOTE(JSON_EXTRACT(s.state_json,'$.livePackage.artifactId'))
        WHERE s.platform='ios' AND a.platform='ios' AND a.available=TRUE AND a.latest_build=#{build}
          AND a.download_url=#{downloadUrl}
          AND a.download_url=JSON_UNQUOTE(JSON_EXTRACT(s.state_json,'$.livePackage.downloadUrl'))
          AND a.sha256=JSON_UNQUOTE(JSON_EXTRACT(s.state_json,'$.livePackage.sha256'))
        """)
    int activeIosUpdate(@Param("build") int build, @Param("downloadUrl") String downloadUrl);

    @Select("""
        SELECT id,notification_id,installation_id,user_id,binding_epoch,attempts,lease_token
        FROM nx_native_push_delivery WHERE
          status IN ('QUEUED','RETRY','BLOCKED') AND next_attempt_at<=NOW(6)
        ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
        """)
    Delivery lockNext();

    // Direct providers have no request-id deduplication contract. A lost worker may already have sent.
    @Update("""
        UPDATE nx_native_push_delivery SET status='UNKNOWN',last_error='WORKER_OUTCOME_UNKNOWN',
          lease_token=NULL,lease_until=NULL,updated_at=NOW(6)
        WHERE status='SENDING' AND lease_until<NOW(6)
        """)
    int expireAmbiguousClaims();

    @Update("""
        UPDATE nx_native_push_delivery SET status='SENDING',attempts=attempts+1,lease_token=#{token},
          lease_until=DATE_ADD(NOW(6),INTERVAL 60 SECOND),
          updated_at=NOW(6) WHERE id=#{id}
        """)
    int claim(@Param("id") long id, @Param("token") String token);

    @Select("""
        SELECT id,notification_id,installation_id,user_id,binding_epoch,attempts,lease_token FROM nx_native_push_delivery
        WHERE id=#{id} AND status='SENDING' AND lease_token=#{token} FOR UPDATE
        """)
    Delivery lockClaim(@Param("id") long id, @Param("token") String token);

    @Update("""
        UPDATE nx_native_push_delivery SET status=#{status},last_error=#{error},provider_task_id=#{task},
          accepted_at=IF(#{status}='ACCEPTED',NOW(6),accepted_at),
          attempts=IF(#{status}='BLOCKED',GREATEST(attempts-1,0),attempts),
          next_attempt_at=DATE_ADD(NOW(6),INTERVAL #{delay} SECOND),
          lease_token=NULL,lease_until=NULL,updated_at=NOW(6)
        WHERE id=#{id} AND status='SENDING' AND lease_token=#{token}
        """)
    int finish(@Param("id") long id, @Param("token") String token, @Param("status") String status,
               @Param("error") String error, @Param("task") String task, @Param("delay") int delay);

    // Recheck latest chain, revocation, fixed APP context, idle TTL and account audience immediately before the HTTP send.
    @Select("""
        SELECT COUNT(*) FROM nx_user_session s JOIN nx_app_session_state a ON a.user_id=s.user_id
          AND a.session_chain_id=s.session_chain_id AND a.app_session_version=s.app_session_version
          AND a.installation_id=s.installation_id AND a.execution_supported=s.execution_supported
        JOIN nx_user u ON u.id=s.user_id AND u.status='ACTIVE' AND u.is_deleted=0 AND u.sandbox=#{sandbox}
        WHERE s.user_id=#{i.userId} AND s.installation_id=#{i.installationId} AND s.session_chain_id=#{i.sessionChainId}
          AND s.session_group='APP' AND s.is_deleted=0 AND s.revoked_at IS NULL AND s.session_ended_reason IS NULL
          AND s.expires_at>NOW()
          AND COALESCE(s.last_active_at,s.created_at)>DATE_SUB(NOW(),INTERVAL #{idleDays} DAY)
          AND NOT EXISTS(SELECT 1 FROM nx_user_security sec WHERE sec.user_id=u.id AND sec.is_deleted=0 AND sec.password_reset_required=1)
          AND NOT EXISTS(SELECT 1 FROM nx_user_session newer WHERE newer.installation_id=s.installation_id AND newer.id>s.id
            AND newer.session_chain_id<>s.session_chain_id AND newer.is_deleted=0 AND newer.session_group='APP')
        """)
    int activeSession(@Param("i") Installation installation, @Param("sandbox") int sandbox, @Param("idleDays") int idleDays);

    @Select("""
        SELECT n.id,n.type,n.cta_href,u.language,n.title,n.body,
          CASE WHEN n.type='APP_UPDATE' THEN CAST(SUBSTRING_INDEX(n.biz_no,':',-1) AS UNSIGNED) END AS update_build
        FROM nx_notification n JOIN nx_user u ON u.id=n.user_id
        WHERE n.id=#{id} AND n.user_id=#{user} AND n.is_deleted=0
          AND n.biz_no NOT LIKE 'STATION1:%'
          AND ((n.type IN ('DEVICE_EXPIRY','DEVICE_ALERT','FUNDS_RESULT','ACCOUNT_SECURITY','SUPPORT_REPLY')
            AND (n.biz_no LIKE 'NATIVE1:%' OR EXISTS(SELECT 1 FROM nx_business_notification_fact f WHERE f.notification_biz_no=n.biz_no AND f.user_id=n.user_id)))
            OR (((n.biz_no LIKE 'NOVA-%' AND LEFT(n.type,5)='NOVA_')
                OR (n.biz_no LIKE 'APP_UPDATE:ios:%' AND n.type='APP_UPDATE')) AND EXISTS
              (SELECT 1 FROM nx_native_push_delivery d WHERE d.notification_id=n.id AND d.user_id=n.user_id)))
        """)
    Notification ownedNotification(@Param("id") long id, @Param("user") long user);

    @Select("""
        SELECT EXISTS(SELECT 1 FROM nx_notification n
          JOIN nx_business_notification_fact f ON f.notification_biz_no=n.biz_no AND f.user_id=n.user_id
          WHERE n.id=#{id} AND n.user_id=#{user} AND f.fact_key LIKE 'SESSION:%')
        """)
    boolean isNewSignInNotice(@Param("id") long id, @Param("user") long user);

    @Select("""
        SELECT COUNT(*) FROM nx_notification n LEFT JOIN nx_user_preference p ON p.user_id=n.user_id AND p.is_deleted=0
        WHERE n.id=#{id} AND n.user_id=#{user} AND n.is_deleted=0
          AND n.biz_no NOT LIKE 'STATION1:%'
          AND n.created_at>DATE_SUB(NOW(6),INTERVAL 24 HOUR)
          AND (LOWER(n.priority)='critical' OR COALESCE(p.notify_system,1)=1)
        """)
    int stillEligible(@Param("id") long id, @Param("user") long user);

    @Select("SELECT COUNT(*) FROM nx_notification n WHERE n.id=#{id} AND " + LIVE_NOVA_CHANNEL)
    int novaChannelEnabled(long id);

    @Update("""
        UPDATE nx_native_push_delivery SET opened_at=COALESCE(opened_at,NOW(6)),
          updated_at=NOW(6)
        WHERE notification_id=#{notification} AND installation_id=#{i.installationId} AND user_id=#{i.userId}
          AND binding_epoch=#{i.bindingEpoch} AND opened_at IS NULL AND attempts>0
          AND status IN ('SENDING','ACCEPTED','UNKNOWN','RETRY','FAILED')
        """)
    int opened(@Param("notification") long notification, @Param("i") Installation installation);

    @Select("SELECT COUNT(*) FROM nx_user_device WHERE id=#{id} AND user_id=#{user} AND is_deleted=0 AND source_environment='PRODUCTION' AND run_id=''")
    int ownedDevice(@Param("id") long id, @Param("user") long user);

    @Select("SELECT COUNT(*) FROM nx_order WHERE order_no=#{number} AND user_id=#{user} AND is_deleted=0")
    int ownedOrder(@Param("number") String number,@Param("user") long user);

    @Select("SELECT COUNT(*) FROM nx_withdrawal_order WHERE withdrawal_no=#{number} AND user_id=#{user} AND is_deleted=0")
    int ownedWithdrawal(@Param("number") String number, @Param("user") long user);

    @Select("SELECT COUNT(*) FROM nx_conversation WHERE conversation_no=#{number} AND user_id=#{user} AND is_deleted=0")
    int ownedConversation(@Param("number") String number, @Param("user") long user);
}
