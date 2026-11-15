package ffdd.opsconsole.developer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Persistence boundary for the admin developer-access governance surface. */
@Mapper
public interface OpsDeveloperAccessMapper extends BaseMapper<Object> {
    @Select("<script>SELECT COUNT(*) FROM nx_developer_access_request WHERE is_deleted=0"
            + "<if test=\"status != null and status != ''\"> AND status=#{status}</if>"
            + "<if test=\"keyword != null and keyword != ''\">"
            + " AND (request_no LIKE CONCAT('%',#{keyword},'%') OR company LIKE CONCAT('%',#{keyword},'%')"
            + " OR email LIKE CONCAT('%',#{keyword},'%') OR use_case LIKE CONCAT('%',#{keyword},'%'))"
            + "</if><if test=\"sourceEnvironment != null and sourceEnvironment != ''\">"
            + " AND source_environment=#{sourceEnvironment}</if></script>")
    long count(@Param("status") String status, @Param("keyword") String keyword, @Param("sourceEnvironment") String sourceEnvironment);

    @Select("<script>SELECT status, COUNT(*) AS count FROM nx_developer_access_request WHERE is_deleted=0"
            + "<if test=\"keyword != null and keyword != ''\">"
            + " AND (request_no LIKE CONCAT('%',#{keyword},'%') OR company LIKE CONCAT('%',#{keyword},'%')"
            + " OR email LIKE CONCAT('%',#{keyword},'%') OR use_case LIKE CONCAT('%',#{keyword},'%'))"
            + "</if><if test=\"sourceEnvironment != null and sourceEnvironment != ''\">"
            + " AND source_environment=#{sourceEnvironment}</if>"
            + " GROUP BY status</script>")
    List<StatusCount> countByStatus(@Param("keyword") String keyword,
                                    @Param("sourceEnvironment") String sourceEnvironment);

    @Select("<script>SELECT request_no requestNo,user_id userId,company,email,use_case useCase,status,"
            + "source_environment sourceEnvironment,run_id runId,reviewer,review_reason reviewReason,"
            + "reviewed_at reviewedAt,created_at createdAt,updated_at updatedAt"
            + " FROM nx_developer_access_request WHERE is_deleted=0"
            + "<if test=\"status != null and status != ''\"> AND status=#{status}</if>"
            + "<if test=\"keyword != null and keyword != ''\">"
            + " AND (request_no LIKE CONCAT('%',#{keyword},'%') OR company LIKE CONCAT('%',#{keyword},'%')"
            + " OR email LIKE CONCAT('%',#{keyword},'%') OR use_case LIKE CONCAT('%',#{keyword},'%'))"
            + "</if><if test=\"sourceEnvironment != null and sourceEnvironment != ''\">"
            + " AND source_environment=#{sourceEnvironment}</if>"
            + "<choose><when test=\"ascending\"> ORDER BY created_at ASC,id ASC</when>"
            + "<otherwise> ORDER BY created_at DESC,id DESC</otherwise></choose>"
            + " LIMIT #{offset},#{limit}</script>")
    List<AccessRow> page(@Param("status") String status, @Param("keyword") String keyword,
                         @Param("sourceEnvironment") String sourceEnvironment,
                         @Param("ascending") boolean ascending,
                         @Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT request_no requestNo,user_id userId,company,email,use_case useCase,status,"
            + "source_environment sourceEnvironment,run_id runId,reviewer,review_reason reviewReason,"
            + "reviewed_at reviewedAt,created_at createdAt,updated_at updatedAt"
            + " FROM nx_developer_access_request WHERE request_no=#{requestNo} AND is_deleted=0 LIMIT 1")
    AccessRow find(@Param("requestNo") String requestNo);

    @Select("SELECT request_no requestNo,user_id userId,company,email,use_case useCase,status,"
            + "source_environment sourceEnvironment,run_id runId,reviewer,review_reason reviewReason,"
            + "reviewed_at reviewedAt,created_at createdAt,updated_at updatedAt"
            + " FROM nx_developer_access_request WHERE request_no=#{requestNo} AND is_deleted=0 FOR UPDATE")
    AccessRow findForUpdate(@Param("requestNo") String requestNo);

    @Select("<script>SELECT id AS userId,"
            + " CONCAT('U', LPAD(id, GREATEST(8, LENGTH(CAST(id AS CHAR))), '0')) AS userNo"
            + " FROM nx_user WHERE is_deleted=0 AND id IN"
            + " <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<UserNoRow> userNos(@Param("ids") List<Long> ids);

    @Select("SELECT request_no requestNo,action,idempotency_key idempotencyKey,request_hash requestHash,status,"
            + "result_status resultStatus,result_reviewer resultReviewer,result_reason resultReason,"
            + "result_reviewed_at resultReviewedAt"
            + " FROM nx_developer_access_review_idempotency"
            + " WHERE request_no=#{requestNo} AND idempotency_key=#{idempotencyKey} LIMIT 1")
    IdempotencyRow findIdempotency(@Param("requestNo") String requestNo,
                                   @Param("idempotencyKey") String idempotencyKey);

    /** INSERT IGNORE is the durable compare-and-set for concurrent first attempts. */
    @Insert("INSERT IGNORE INTO nx_developer_access_review_idempotency"
            + " (request_no,action,idempotency_key,request_hash,status)"
            + " VALUES(#{requestNo},#{action},#{idempotencyKey},#{requestHash},'PENDING')")
    int insertIdempotency(@Param("requestNo") String requestNo, @Param("action") String action,
                          @Param("idempotencyKey") String idempotencyKey, @Param("requestHash") String requestHash);

    @Update("UPDATE nx_developer_access_review_idempotency SET status='COMPLETED',result_status=#{resultStatus},"
            + "result_reviewer=#{resultReviewer},result_reason=#{resultReason},"
            + "result_reviewed_at=#{resultReviewedAt},updated_at=NOW()"
            + " WHERE request_no=#{requestNo} AND action=#{action} AND idempotency_key=#{idempotencyKey}"
            + " AND request_hash=#{requestHash} AND status='PENDING'")
    int completeIdempotency(@Param("requestNo") String requestNo, @Param("action") String action,
                            @Param("idempotencyKey") String idempotencyKey, @Param("requestHash") String requestHash,
                            @Param("resultStatus") String resultStatus, @Param("resultReviewer") String resultReviewer,
                            @Param("resultReason") String resultReason, @Param("resultReviewedAt") LocalDateTime resultReviewedAt);

    @Delete("DELETE FROM nx_developer_access_review_idempotency"
            + " WHERE request_no=#{requestNo} AND idempotency_key=#{idempotencyKey}"
            + " AND request_hash=#{requestHash} AND status='PENDING'")
    int deletePendingIdempotency(@Param("requestNo") String requestNo, @Param("idempotencyKey") String idempotencyKey,
                                 @Param("requestHash") String requestHash);

    /** Status and deletion predicates form the CAS fence; zero rows means a stale operation. */
    @Update("UPDATE nx_developer_access_request SET status=#{toStatus},reviewer=#{reviewer},"
            + "review_reason=#{reason},reviewed_at=NOW(),updated_at=NOW()"
            + " WHERE request_no=#{requestNo} AND status=#{fromStatus} AND is_deleted=0")
    int transition(@Param("requestNo") String requestNo, @Param("fromStatus") String fromStatus,
                   @Param("toStatus") String toStatus, @Param("reviewer") String reviewer,
                   @Param("reason") String reason, @Param("idempotencyKey") String idempotencyKey);

    record AccessRow(String requestNo, Long userId, String company, String email, String useCase,
                    String status, String sourceEnvironment, String runId, String reviewer,
                    String reviewReason, LocalDateTime reviewedAt, LocalDateTime createdAt,
                    LocalDateTime updatedAt) { }

    record UserNoRow(Long userId, String userNo) { }

    record StatusCount(String status, long count) { }

    record IdempotencyRow(String requestNo, String action, String idempotencyKey,
                          String requestHash, String status, String resultStatus,
                          String resultReviewer, String resultReason, LocalDateTime resultReviewedAt) { }
}
