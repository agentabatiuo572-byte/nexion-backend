package ffdd.opsconsole.content.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** Two durable records only: job/asset headers and frozen individual recipients. */
// Job headers and composite-key recipients share guarded SQL, not a single BaseMapper CRUD entity.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportBulkMapper {
    String HEADER = "id,record_type recordType,actor_id actorId,state,version,command_key commandKey,"
        + "client_upload_id clientUploadId,request_hash requestHash,selection_mode selectionMode,filters_json filtersJson,"
        + "excluded_json excludedJson,content_json contentJson,asset_id assetId,asset_json assetJson,"
        + "frozen_count frozenCount,cancel_requested cancelRequested,evaluated_at evaluatedAt,expires_at expiresAt,"
        + "created_at createdAt,updated_at updatedAt";
    String RECIPIENT = "r.batch_id batchId,r.customer_id customerId,r.expected_assignment_id expectedAssignmentId,"
        + "r.client_message_id clientMessageId,r.attachment_id attachmentId,r.operation,r.conversation_no conversationNo,"
        + "r.request_json requestJson,r.state,r.result_certainty resultCertainty,r.message_id messageId,"
        + "r.failure_code failureCode,r.retryable,r.attempts,r.created_at createdAt,r.updated_at updatedAt";

    @Select("<script>" + SupportWorkbenchMapper.PROJECTION + """
        SELECT c.*,c.activityStatus accountState,COALESCE(NULLIF(u.v_rank,''),NULLIF(u.user_level,'')) level,
          CONVERT_TZ(u.created_at,'+08:00','+00:00') registeredAt,(#{maintenanceDays} IS NOT NULL) maintenanceConfigured
        FROM customers c JOIN nx_user u ON u.id=c.customerId WHERE 1=1
        <if test='ids != null'>AND c.customerId IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></if>
        ORDER BY c.customerId</script>
        """)
    List<Map<String,Object>> candidates(Map<String,Object> query);

    @Select("SELECT COUNT(*) FROM nx_customer_tag WHERE user_id=#{customer} AND is_deleted=0 AND tag=#{tag}")
    int hasTag(@Param("customer") Long customer,@Param("tag") String tag);

    @Select("""
        SELECT conversation_no conversationNo,status,version,archived,
          EXISTS(SELECT 1 FROM nx_support_ticket t WHERE t.source_conversation_no=c.conversation_no AND t.is_deleted=0) converted
        FROM nx_conversation c WHERE user_id=#{customer} AND conversation_type='advisor' AND is_deleted=0
        ORDER BY id DESC LIMIT 1
        """)
    Map<String,Object> conversation(Long customer);

    @Select("SELECT COALESCE(p.enabled,1) FROM nx_user u LEFT JOIN nx_support_maintenance_preference p ON p.customer_id=u.id WHERE u.id=#{customer} AND u.is_deleted=0")
    Boolean maintenanceEnabled(Long customer);

    @Select("""
        SELECT COUNT(*) FROM nx_admin a JOIN nx_admin_role_relation rr ON rr.admin_id=a.id AND rr.is_deleted=0
          JOIN nx_admin_role r ON r.id=rr.role_id AND r.status=1 AND r.is_deleted=0
          JOIN nx_admin_role_permission rp ON rp.role_id=r.id AND rp.is_deleted=0
          JOIN nx_admin_permission p ON p.id=rp.permission_id AND p.status=1 AND p.is_deleted=0
        WHERE a.id=#{actor} AND a.status=1 AND a.is_deleted=0 AND p.resource_type='API'
          AND (p.permission_code='service_m3_read' OR (#{supervisor} AND p.permission_code='service_m1_read'))
        FOR SHARE
        """)
    int readerGrant(@Param("actor") Long actor,@Param("supervisor") boolean supervisor);

    @Select("""
        SELECT COUNT(*) FROM nx_admin a JOIN nx_admin_role_relation rr ON rr.admin_id=a.id AND rr.is_deleted=0
          JOIN nx_admin_role r ON r.id=rr.role_id AND r.status=1 AND r.is_deleted=0
          JOIN nx_admin_role_permission rp ON rp.role_id=r.id AND rp.is_deleted=0
          JOIN nx_admin_permission p ON p.id=rp.permission_id AND p.status=1 AND p.is_deleted=0
        WHERE a.id=#{actor} AND a.status=1 AND a.is_deleted=0 AND p.resource_type='API' AND p.permission_code='service_m3_write'
        """)
    int writerGrantSnapshot(Long actor);

    @Insert("""
        INSERT INTO nx_support_bulk_job(id,record_type,actor_id,state,selection_mode,filters_json,excluded_json,
          frozen_count,evaluated_at,expires_at,created_at,updated_at)
        VALUES(#{id},'JOB',#{actor},'DRAFT',#{mode},#{filters},#{excluded},#{count},#{at},#{expires},UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
        """)
    int insertPreview(@Param("id") String id,@Param("actor") Long actor,@Param("mode") String mode,
        @Param("filters") String filters,@Param("excluded") String excluded,@Param("count") int count,
        @Param("at") LocalDateTime at,@Param("expires") LocalDateTime expires);

    @Insert("""
        INSERT INTO nx_support_bulk_recipient(batch_id,customer_id,expected_assignment_id,client_message_id)
        VALUES(#{batch},#{customer},#{assignment},#{client})
        """)
    int insertRecipient(@Param("batch") String batch,@Param("customer") Long customer,
        @Param("assignment") Long assignment,@Param("client") String client);

    @Select("SELECT " + HEADER + " FROM nx_support_bulk_job WHERE id=#{id} AND record_type='JOB'")
    Map<String,Object> job(String id);
    @Select("SELECT " + HEADER + " FROM nx_support_bulk_job WHERE id=#{id} AND record_type='JOB' FOR UPDATE")
    Map<String,Object> jobForUpdate(String id);
    @Select("SELECT " + HEADER + " FROM nx_support_bulk_job WHERE actor_id=#{actor} AND record_type='JOB' AND command_key=#{key}")
    Map<String,Object> jobByCommand(@Param("actor") Long actor,@Param("key") String key);
    @Update("""
        UPDATE nx_support_bulk_job SET state='QUEUED',command_key=#{key},request_hash=#{hash},content_json=#{content},
          asset_id=#{asset},version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND state='DRAFT'
        """)
    int queue(@Param("id") String id,@Param("key") String key,@Param("hash") String hash,
        @Param("content") String content,@Param("asset") String asset);
    @Update("UPDATE nx_support_bulk_job SET state=#{state},cancel_requested=#{cancel},version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=#{id}")
    int transition(@Param("id") String id,@Param("state") String state,@Param("cancel") boolean cancel);

    @Select("SELECT " + RECIPIENT + " FROM nx_support_bulk_recipient r WHERE r.batch_id=#{batch} ORDER BY r.customer_id")
    List<Map<String,Object>> allRecipients(String batch);
    @Select("SELECT " + RECIPIENT + " FROM nx_support_bulk_recipient r WHERE r.batch_id=#{batch} AND r.customer_id=#{customer} FOR UPDATE")
    Map<String,Object> recipientForUpdate(@Param("batch") String batch,@Param("customer") Long customer);
    @Update("UPDATE nx_support_bulk_recipient SET operation=#{operation},conversation_no=#{no},attachment_id=#{attachment},updated_at=UTC_TIMESTAMP(6) WHERE batch_id=#{batch} AND customer_id=#{customer} AND operation IS NULL")
    int route(@Param("batch") String batch,@Param("customer") Long customer,@Param("operation") String operation,
        @Param("no") String no,@Param("attachment") String attachment);
    @Update("UPDATE nx_support_bulk_recipient SET request_json=COALESCE(request_json,#{payload}),updated_at=UTC_TIMESTAMP(6) WHERE batch_id=#{batch} AND customer_id=#{customer}")
    int freezeRequest(@Param("batch") String batch,@Param("customer") Long customer,@Param("payload") String payload);
    @Update("""
        UPDATE nx_support_bulk_recipient SET state=#{state},result_certainty=#{certainty},message_id=#{message},
          conversation_no=COALESCE(#{no},conversation_no),failure_code=#{failure},retryable=#{retryable},
          attempts=attempts+#{attempt},updated_at=UTC_TIMESTAMP(6) WHERE batch_id=#{batch} AND customer_id=#{customer}
        """)
    int outcome(@Param("batch") String batch,@Param("customer") Long customer,@Param("state") String state,
        @Param("certainty") String certainty,@Param("message") Long message,@Param("no") String no,
        @Param("failure") String failure,@Param("retryable") boolean retryable,@Param("attempt") int attempt);

    @Select("""
        SELECT COUNT(*) total,COALESCE(SUM(state='PENDING'),0) pending,COALESCE(SUM(state='SENT'),0) sent,
          COALESCE(SUM(state='FAILED'),0) failed,COALESCE(SUM(state='SKIPPED'),0) skipped,
          COALESCE(SUM(state='CANCELLED'),0) cancelled,COALESCE(SUM(result_certainty='UNKNOWN'),0) unknown
        FROM nx_support_bulk_recipient WHERE batch_id=#{batch}
        """)
    Map<String,Object> counts(String batch);
    @SelectProvider(type=ScopedSql.class,method="recipients")
    List<Map<String,Object>> scopedRecipients(Map<String,Object> query);
    @SelectProvider(type=ScopedSql.class,method="recipientCount")
    long scopedRecipientCount(Map<String,Object> query);
    @SelectProvider(type=ScopedSql.class,method="counts")
    Map<String,Object> scopedCounts(Map<String,Object> query);
    @SelectProvider(type=ScopedSql.class,method="jobs")
    List<Map<String,Object>> scopedJobs(Map<String,Object> query);
    @SelectProvider(type=ScopedSql.class,method="jobCount")
    long scopedJobCount(Map<String,Object> query);

    /** Fixed server parameters only; each arm reuses the same current authorization predicate. */
    final class ScopedSql {
        private ScopedSql() {}
        private static String customer(String parameter) {
            return "EXISTS(SELECT 1 FROM nx_user scope_customer WHERE scope_customer.id=r.customer_id "
                    + SupportBindingMapper.CUSTOMER_SCOPE_PREDICATE.replaceAll("\\bscope\\b",parameter) + ")";
        }
        private static String readable() {
            return "("+customer("scope")+" OR "+customer("managedScope")+" OR "+customer("personalScope")+")";
        }
        private static String recipientWhere() {return " WHERE r.batch_id=#{batch} AND "+readable();}
        private static String jobWhere() {
            return " WHERE j.record_type='JOB' AND j.state&lt;&gt;'DRAFT' AND (EXISTS(SELECT 1 FROM nx_support_bulk_recipient r "
                    + "WHERE r.batch_id=j.id AND "+readable()+") "
                    + "<if test=\"senderSummary == true and scope != null and scope.mode.name() == 'PERSONAL'\">OR j.actor_id=#{scope.actorId}</if>)";
        }
        public static String recipients() {
            return "<script>SELECT "+RECIPIENT+" FROM nx_support_bulk_recipient r"+recipientWhere()
                    +" ORDER BY r.customer_id LIMIT #{limit} OFFSET #{offset}</script>";
        }
        public static String recipientCount() {
            return "<script>SELECT COUNT(*) FROM nx_support_bulk_recipient r"+recipientWhere()+"</script>";
        }
        public static String counts() {
            return "<script>SELECT COUNT(*) total,COALESCE(SUM(r.state='PENDING'),0) pending,COALESCE(SUM(r.state='SENT'),0) sent,"
                    +"COALESCE(SUM(r.state='FAILED'),0) failed,COALESCE(SUM(r.state='SKIPPED'),0) skipped,"
                    +"COALESCE(SUM(r.state='CANCELLED'),0) cancelled,COALESCE(SUM(r.result_certainty='UNKNOWN'),0) unknown "
                    +"FROM nx_support_bulk_recipient r"+recipientWhere()+"</script>";
        }
        public static String jobs() {
            return "<script>SELECT "+HEADER+" FROM nx_support_bulk_job j"+jobWhere()
                    +" ORDER BY j.created_at DESC,j.id DESC LIMIT #{limit} OFFSET #{offset}</script>";
        }
        public static String jobCount() {return "<script>SELECT COUNT(*) FROM nx_support_bulk_job j"+jobWhere()+"</script>";}
    }
    @Select("""
        SELECT r.batch_id batchId,r.customer_id customerId FROM nx_support_bulk_recipient r
        JOIN nx_support_bulk_job j ON j.id=r.batch_id WHERE j.record_type='JOB'
          AND j.state IN ('QUEUED','RUNNING') AND r.state='PENDING'
          AND NOT (r.result_certainty='UNKNOWN' AND COALESCE(r.failure_code,'')='SUPPORT_BULK_PAYLOAD_REVIEW_REQUIRED')
        ORDER BY j.created_at,j.id,r.customer_id LIMIT 100
        """)
    List<Map<String,Object>> pending();

    @Select("SELECT " + HEADER + " FROM nx_support_bulk_job WHERE record_type='ASSET' AND id=#{id}")
    Map<String,Object> asset(String id);
    @Select("SELECT " + HEADER + " FROM nx_support_bulk_job WHERE record_type='ASSET' AND id=#{id} FOR UPDATE")
    Map<String,Object> assetForUpdate(String id);
    @Select("SELECT " + HEADER + " FROM nx_support_bulk_job WHERE record_type='ASSET' AND actor_id=#{actor} AND command_key=#{key}")
    Map<String,Object> assetByCommand(@Param("actor") Long actor,@Param("key") String key);
    @Select("SELECT " + HEADER + " FROM nx_support_bulk_job WHERE record_type='ASSET' AND actor_id=#{actor} AND client_upload_id=#{upload}")
    Map<String,Object> assetByUpload(@Param("actor") Long actor,@Param("upload") String upload);
    @Insert("""
        INSERT INTO nx_support_bulk_job(id,record_type,actor_id,state,command_key,client_upload_id,request_hash,asset_json,expires_at,created_at,updated_at)
        VALUES(#{id},'ASSET',#{actor},'READY',#{key},#{upload},#{hash},#{assetJson},#{expires},UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
        """)
    int insertAsset(@Param("id") String id,@Param("actor") Long actor,@Param("key") String key,
        @Param("upload") String upload,@Param("hash") String hash,@Param("assetJson") String assetJson,@Param("expires") LocalDateTime expires);
    @Update("UPDATE nx_support_bulk_job SET state=#{state},version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE record_type='ASSET' AND id=#{id}")
    int retireAsset(@Param("id") String id,@Param("state") String state);
    @Select("SELECT " + HEADER + " FROM nx_support_bulk_job WHERE record_type='ASSET' AND state='READY' AND expires_at<=UTC_TIMESTAMP(6) ORDER BY expires_at,id LIMIT 100")
    List<Map<String,Object>> expiredAssets();
}
