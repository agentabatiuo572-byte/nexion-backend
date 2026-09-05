package ffdd.opsconsole.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AppAmbassadorApplicationMapper extends BaseMapper<Object> {
    @Select("SELECT sandbox,v_rank vRank,nickname,region FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    UserScope lockUser(@Param("userId") Long userId);

    @Select("SELECT sandbox,v_rank vRank,nickname,region FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    UserScope user(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*) FROM nx_user u
             WHERE u.id=#{userId}
               AND REPLACE(TRIM(COALESCE(u.country_code,'')),'+','')=REPLACE(#{countryCode},'+','')
               AND u.phone=#{phone} AND u.sandbox=1
               AND u.status='ACTIVE' AND u.is_deleted=0
            """)
    int developmentUserScope(@Param("userId") Long userId,
                             @Param("countryCode") String countryCode,
                             @Param("phone") String phone);

    @Select("""
            SELECT id,user_id userId,idempotency_key idempotencyKey,request_hash requestHash,
                   UPPER(status) status,city,event_date eventDate,requested_budget_usdt budget,
                   application_reason bucket,created_at submittedAt,
                   source_environment sourceEnvironment,run_id runId
              FROM nx_team_ambassador_application
             WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId}
               AND idempotency_key=#{key} AND is_deleted=0 LIMIT 1
            """)
    ApplicationRow findByKey(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment,
                             @Param("runId") String runId, @Param("key") String key);

    @Select("""
            SELECT id,user_id userId,idempotency_key idempotencyKey,request_hash requestHash,
                   UPPER(status) status,city,event_date eventDate,requested_budget_usdt budget,
                   application_reason bucket,created_at submittedAt,
                   source_environment sourceEnvironment,run_id runId
              FROM nx_team_ambassador_application
             WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId}
               AND is_deleted=0 ORDER BY created_at DESC,id DESC LIMIT 1
            """)
    ApplicationRow latest(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment,
                          @Param("runId") String runId);

    @Select("SELECT COUNT(*) FROM nx_team_ambassador_application WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND is_deleted=0")
    long count(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment,
               @Param("runId") String runId);

    @Select("""
            SELECT id,user_id userId,idempotency_key idempotencyKey,request_hash requestHash,
                   UPPER(status) status,city,event_date eventDate,requested_budget_usdt budget,
                   application_reason bucket,created_at submittedAt,
                   source_environment sourceEnvironment,run_id runId
              FROM nx_team_ambassador_application
             WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId}
               AND is_deleted=0 ORDER BY created_at DESC,id DESC LIMIT #{offset},#{limit}
            """)
    List<ApplicationRow> list(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment,
                              @Param("runId") String runId, @Param("offset") long offset,
                              @Param("limit") int limit);

    @Select("""
            SELECT id,user_id userId,idempotency_key idempotencyKey,request_hash requestHash,
                   UPPER(status) status,city,event_date eventDate,requested_budget_usdt budget,
                   application_reason bucket,created_at submittedAt,
                   source_environment sourceEnvironment,run_id runId
              FROM nx_team_ambassador_application
             WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId}
               AND UPPER(status)='PENDING' AND is_deleted=0 ORDER BY created_at DESC,id DESC LIMIT 1
            """)
    ApplicationRow pending(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment,
                           @Param("runId") String runId);

    @Insert("""
            INSERT IGNORE INTO nx_team_ambassador_application
              (user_id,applicant_name,region,city,event_date,current_rank,requested_budget_usdt,
               application_reason,status,idempotency_key,request_hash,source_environment,run_id,
               created_at,updated_at,is_deleted)
            VALUES
              (#{userId},#{applicantName},#{region},#{city},#{eventDate},#{currentRank},#{budget},
               #{bucket},'PENDING',#{idempotencyKey},#{requestHash},#{sourceEnvironment},#{runId},NOW(),NOW(),0)
            """)
    int insertApplication(ApplicationWrite write);

    record UserScope(Integer sandbox, String vRank, String nickname, String region) { }
    record ApplicationWrite(Long userId, String applicantName, String region, String currentRank,
                            LocalDate eventDate, String city, BigDecimal budget, String bucket,
                            String idempotencyKey, String requestHash, String sourceEnvironment, String runId) { }
    record ApplicationRow(Long id, Long userId, String idempotencyKey, String requestHash, String status,
                          String city, LocalDate eventDate, BigDecimal budget, String bucket,
                          LocalDateTime submittedAt, String sourceEnvironment, String runId) { }
}
