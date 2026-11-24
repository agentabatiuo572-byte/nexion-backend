package ffdd.opsconsole.content.performanceexport.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** B80-BE-06 export tasks (one row per type under a shared request id), decisions and tokens. */
@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportPerformanceExportMapper {
    record TaskRow(Long id, String requestId, Long requesterId, String taskType, String scopeJson,
                   String windowJson, String previewVersion, String requestReason, String status,
                   Integer version, Integer rowCount, Integer restrictedRows, String columnsJson,
                   String coverageJson, String contentJson, String disclosedCustomersJson,
                   String generationError, Long reviewerId, String decisionReason, LocalDateTime approvedAt,
                   String fileName, LocalDateTime fileCreatedAt, LocalDateTime deliveredAt,
                   LocalDateTime createdAt) { }

    @Insert("""
            INSERT INTO nx_support_performance_export_task
              (request_id, requester_id, task_type, scope_json, window_json, preview_version,
               request_reason, status, version, row_count, restricted_rows, columns_json, coverage_json,
               content_json, disclosed_customers_json, created_at, updated_at, is_deleted)
            VALUES (#{requestId}, #{requesterId}, #{taskType}, CAST(#{scopeJson} AS JSON),
                    CAST(#{windowJson} AS JSON), #{previewVersion}, #{requestReason}, #{status}, 1,
                    #{rowCount}, #{restrictedRows}, CAST(#{columnsJson} AS JSON),
                    CAST(#{coverageJson} AS JSON), #{contentJson}, CAST(#{disclosedCustomersJson} AS JSON),
                    NOW(), NOW(), 0)
            """)
    int insertTask(@Param("requestId") String requestId, @Param("requesterId") Long requesterId,
                   @Param("taskType") String taskType, @Param("scopeJson") String scopeJson,
                   @Param("windowJson") String windowJson, @Param("previewVersion") String previewVersion,
                   @Param("requestReason") String requestReason, @Param("status") String status,
                   @Param("rowCount") Integer rowCount, @Param("restrictedRows") Integer restrictedRows,
                   @Param("columnsJson") String columnsJson, @Param("coverageJson") String coverageJson,
                   @Param("contentJson") String contentJson, @Param("disclosedCustomersJson") String disclosedCustomersJson);

    @Insert("INSERT INTO nx_support_performance_export_request(request_key, requester_id, created_at)"
            + " VALUES(#{requestKey}, #{requesterId}, NOW())")
    int insertRequest(@Param("requestKey") String requestKey, @Param("requesterId") Long requesterId);

    /** The batch id of this command only; parallel commands from one account never cross. */
    @Select("SELECT LAST_INSERT_ID()")
    Long lastInsertId();

    /** An original command key or the numeric batch id both locate this account's batch. */
    @Select("<script>SELECT id FROM nx_support_performance_export_request WHERE requester_id=#{requesterId}"
            + " AND (request_key=#{key}<if test='numeric'> OR CAST(id AS CHAR)=#{key}</if>)</script>")
    java.util.List<Long> requestIdsForKey(@Param("requesterId") Long requesterId, @Param("key") String key,
                                          @Param("numeric") boolean numeric);

    @Select("SELECT " + "id,request_id AS requestId,requester_id AS requesterId,task_type AS taskType,"
            + "scope_json AS scopeJson,window_json AS windowJson,preview_version AS previewVersion,"
            + "request_reason AS requestReason,status,version,row_count AS rowCount,"
            + "restricted_rows AS restrictedRows,columns_json AS columnsJson,coverage_json AS coverageJson,"
            + "content_json AS contentJson,disclosed_customers_json AS disclosedCustomersJson,"
            + "generation_error AS generationError,reviewer_id AS reviewerId,"
            + "decision_reason AS decisionReason,approved_at AS approvedAt,file_name AS fileName,"
            + "file_created_at AS fileCreatedAt,delivered_at AS deliveredAt,created_at AS createdAt"
            + " FROM nx_support_performance_export_task WHERE request_id=#{requestId} AND is_deleted=0 ORDER BY id")
    java.util.List<TaskRow> listByRequest(@Param("requestId") String requestId);

    String TASK_COLUMNS = "id,request_id AS requestId,requester_id AS requesterId,task_type AS taskType,"
            + "scope_json AS scopeJson,window_json AS windowJson,preview_version AS previewVersion,"
            + "request_reason AS requestReason,status,version,row_count AS rowCount,"
            + "restricted_rows AS restrictedRows,columns_json AS columnsJson,coverage_json AS coverageJson,"
            + "content_json AS contentJson,disclosed_customers_json AS disclosedCustomersJson,"
            + "generation_error AS generationError,reviewer_id AS reviewerId,"
            + "decision_reason AS decisionReason,approved_at AS approvedAt,file_name AS fileName,"
            + "file_created_at AS fileCreatedAt,delivered_at AS deliveredAt,created_at AS createdAt";

    @Select("SELECT " + TASK_COLUMNS + " FROM nx_support_performance_export_task WHERE id=#{id} AND is_deleted=0")
    TaskRow findTask(@Param("id") Long id);

    @Select("SELECT " + TASK_COLUMNS + " FROM nx_support_performance_export_task"
            + " WHERE status='APPROVED' AND is_deleted=0 ORDER BY approved_at,id LIMIT 10")
    java.util.List<TaskRow> approvedForGeneration();

    @Select("SELECT " + TASK_COLUMNS + " FROM nx_support_performance_export_task"
            + " WHERE request_id=#{requestId} AND task_type=#{taskType} AND is_deleted=0 LIMIT 1")
    TaskRow findTaskByRequestAndType(@Param("requestId") String requestId, @Param("taskType") String taskType);

    String MINE_FILTER = " FROM nx_support_performance_export_task"
            + " WHERE requester_id=#{requesterId} AND is_deleted=0 AND task_type IN"
            + "<foreach collection='types' item='type' open='(' separator=',' close=')'>#{type}</foreach>"
            + "<if test='requestIds != null'> AND request_id IN"
            + "<foreach collection='requestIds' item='rid' open='(' separator=',' close=')'>#{rid}</foreach></if>";

    @Select("<script>SELECT " + TASK_COLUMNS + MINE_FILTER
            + " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<TaskRow> listMine(@Param("requesterId") Long requesterId, @Param("types") List<String> types,
                           @Param("requestIds") List<String> requestIds,
                           @Param("offset") int offset, @Param("limit") int limit);

    @Select("<script>SELECT COUNT(*)" + MINE_FILTER + "</script>")
    long countMine(@Param("requesterId") Long requesterId, @Param("types") List<String> types,
                   @Param("requestIds") List<String> requestIds);

    /** Every frozen group of the task must sit inside the reviewer's current groups. */
    String APPROVAL_FILTER = " FROM nx_support_performance_export_task"
            + " WHERE status='PENDING_APPROVAL' AND task_type IN ('CUSTOMER_DETAIL','TRANSACTION_DETAIL')"
            + " AND is_deleted=0"
            + "<if test='!allScope'> AND JSON_UNQUOTE(JSON_EXTRACT(scope_json,'$.kind'))='MANAGED_GROUPS'"
            + "<if test='groupIds == null or groupIds.isEmpty()'> AND 1=0</if>"
            + "<if test='groupIds != null and !groupIds.isEmpty()'> AND NOT EXISTS(SELECT 1 FROM JSON_TABLE("
            + "JSON_EXTRACT(scope_json,'$.groupIds'),'$[*]' COLUMNS(frozen VARCHAR(64) PATH '$')) frozen_groups"
            + " WHERE frozen_groups.frozen NOT IN"
            + "<foreach collection='groupIds' item='gid' open='(' separator=',' close=')'>#{gid}</foreach>)</if></if>";

    @Select("<script>SELECT " + TASK_COLUMNS + APPROVAL_FILTER
            + " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<TaskRow> listApprovals(@Param("allScope") boolean allScope, @Param("groupIds") List<String> groupIds,
                                @Param("offset") int offset, @Param("limit") int limit);

    @Select("<script>SELECT COUNT(*)" + APPROVAL_FILTER + "</script>")
    long countApprovals(@Param("allScope") boolean allScope, @Param("groupIds") List<String> groupIds);

    @Update("""
            UPDATE nx_support_performance_export_task
               SET status=#{status}, reviewer_id=#{reviewerId}, decision_reason=#{reason},
                   approved_at=#{approvedAt}, generation_error=NULL, version=version+1, updated_at=NOW()
             WHERE id=#{id} AND version=#{expectedVersion} AND status='PENDING_APPROVAL' AND is_deleted=0
            """)
    int decide(@Param("id") Long id, @Param("expectedVersion") int expectedVersion,
               @Param("status") String status, @Param("reviewerId") Long reviewerId,
               @Param("reason") String reason, @Param("approvedAt") LocalDateTime approvedAt);

    @Update("""
            UPDATE nx_support_performance_export_task
               SET status='FAILED', generation_error=#{reason}, version=version+1, updated_at=NOW()
             WHERE id=#{id} AND status='APPROVED' AND is_deleted=0
            """)
    int markFailed(@Param("id") Long id, @Param("reason") String reason);

    @Update("""
            UPDATE nx_support_performance_export_task
               SET status='WITHDRAWN', decision_reason=#{reason}, version=version+1, updated_at=NOW()
             WHERE id=#{id} AND requester_id=#{requesterId} AND version=#{expectedVersion}
               AND status='PENDING_APPROVAL' AND task_type IN ('CUSTOMER_DETAIL','TRANSACTION_DETAIL')
               AND is_deleted=0
            """)
    int withdraw(@Param("id") Long id, @Param("requesterId") Long requesterId,
                 @Param("expectedVersion") int expectedVersion, @Param("reason") String reason);

    @Update("""
            UPDATE nx_support_performance_export_task
               SET status='EXPIRED', file_csv=NULL, content_json=NULL, version=version+1, updated_at=NOW()
             WHERE status='READY' AND file_created_at <= #{cutoff} AND is_deleted=0
             ORDER BY file_created_at, id LIMIT 200
            """)
    int expireFiles(@Param("cutoff") LocalDateTime cutoff);

    @Update("""
            UPDATE nx_support_performance_export_task
               SET status='READY', file_csv=#{csv}, file_name=#{fileName}, file_created_at=#{now},
                   version=version+1, updated_at=NOW()
             WHERE id=#{id} AND status=#{expectedStatus} AND is_deleted=0
            """)
    int markReady(@Param("id") Long id, @Param("expectedStatus") String expectedStatus,
                  @Param("csv") String csv, @Param("fileName") String fileName,
                  @Param("now") LocalDateTime now);

    @Select("SELECT file_csv FROM nx_support_performance_export_task WHERE id=#{id} AND status='READY' AND is_deleted=0")
    String fileCsv(@Param("id") Long id);

    @Select("SELECT file_created_at FROM nx_support_performance_export_task WHERE id=#{id} AND status='READY' AND is_deleted=0")
    LocalDateTime fileCreatedAt(@Param("id") Long id);

    @Update("UPDATE nx_support_performance_export_task SET delivered_at=#{now}, updated_at=NOW() WHERE id=#{id} AND is_deleted=0")
    int markDelivered(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO nx_support_performance_export_token(token_hash, task_id, requester_id, expires_at, created_at)
            VALUES (#{tokenHash}, #{taskId}, #{requesterId}, #{expiresAt}, NOW())
            """)
    int insertToken(@Param("tokenHash") String tokenHash, @Param("taskId") Long taskId,
                    @Param("requesterId") Long requesterId, @Param("expiresAt") LocalDateTime expiresAt);

    @Select("""
            SELECT COUNT(*) FROM nx_support_performance_export_token
             WHERE token_hash=#{tokenHash} AND task_id=#{taskId} AND requester_id=#{requesterId}
               AND used_at IS NULL AND expires_at>#{now}
            """)
    int validToken(@Param("tokenHash") String tokenHash, @Param("taskId") Long taskId,
                   @Param("requesterId") Long requesterId, @Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(*) FROM nx_support_performance_export_token
             WHERE token_hash=#{tokenHash} AND task_id=#{taskId} AND requester_id=#{requesterId}
            """)
    int tokenExists(@Param("tokenHash") String tokenHash, @Param("taskId") Long taskId,
                    @Param("requesterId") Long requesterId);

    @Select("SELECT COUNT(*) FROM nx_support_performance_export_token WHERE token_hash=#{tokenHash} AND expires_at<=#{now}")
    int tokenExpired(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

    @Update("UPDATE nx_support_performance_export_token SET used_at=#{now} WHERE token_hash=#{tokenHash} AND used_at IS NULL AND expires_at > #{now}")
    int useToken(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);
}
