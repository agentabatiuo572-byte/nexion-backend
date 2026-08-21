package ffdd.opsconsole.developer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AppDeveloperAccessMapper extends BaseMapper<Object> {
    @Select("SELECT sandbox FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    Integer userSandbox(@Param("userId") Long userId);
    @Select("SELECT sandbox FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    Integer lockUserSandbox(@Param("userId") Long userId);
    @Select("SELECT COUNT(*) FROM nx_developer_access_request WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND status='APPROVED' AND is_deleted=0")
    int approved(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment,
                 @Param("runId") String runId);
    @Select("SELECT request_no requestNo,user_id userId,idempotency_key idempotencyKey,request_hash requestHash,status,created_at submittedAt,source_environment sourceEnvironment,run_id runId FROM nx_developer_access_request WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND idempotency_key=#{key} AND is_deleted=0 LIMIT 1")
    AccessRow findByKey(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment,
                        @Param("runId") String runId, @Param("key") String key);
    @Select("SELECT request_no requestNo,user_id userId,idempotency_key idempotencyKey,request_hash requestHash,status,created_at submittedAt,source_environment sourceEnvironment,run_id runId FROM nx_developer_access_request WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND is_deleted=0 ORDER BY created_at DESC,id DESC LIMIT 1")
    AccessRow latest(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment,
                     @Param("runId") String runId);
    @Select("SELECT request_no requestNo,user_id userId,idempotency_key idempotencyKey,request_hash requestHash,status,created_at submittedAt,source_environment sourceEnvironment,run_id runId FROM nx_developer_access_request WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND status='PENDING' AND is_deleted=0 ORDER BY created_at DESC,id DESC LIMIT 1")
    AccessRow pending(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment,
                      @Param("runId") String runId);
    @Insert("INSERT IGNORE INTO nx_developer_access_request(request_no,user_id,idempotency_key,request_hash,company,email,use_case,status,source_environment,run_id,created_at,updated_at,is_deleted) VALUES(#{requestNo},#{userId},#{idempotencyKey},#{requestHash},#{company},#{email},#{useCase},'PENDING',#{sourceEnvironment},#{runId},NOW(),NOW(),0)")
    int insertRequest(AccessWrite write);
    record AccessWrite(String requestNo, Long userId, String idempotencyKey, String requestHash, String company,
                       String email, String useCase, String sourceEnvironment, String runId) { }
    record AccessRow(String requestNo, Long userId, String idempotencyKey, String requestHash, String status,
                     LocalDateTime submittedAt, String sourceEnvironment, String runId) { }
}
