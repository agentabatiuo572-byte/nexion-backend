package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.domain.SupportAgentAssignmentView;
import ffdd.opsconsole.content.domain.DedicatedAdvisorBindingView;
import ffdd.opsconsole.content.domain.SupportTicketAssigneeCandidateView;
import ffdd.opsconsole.content.domain.SupportAgentRepository.SupportOperatorRecord;
import ffdd.opsconsole.content.domain.SupportAgentRepository.SupportOperatorScope;
import ffdd.opsconsole.content.infrastructure.SupportAgentProfileEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface SupportAgentMapper extends BaseMapper<SupportAgentProfileEntity> {
    @Select("""
            SELECT id, role_code AS roleCode FROM nx_admin_role
             WHERE status = 1 AND is_deleted = 0 ORDER BY id ASC
            """)
    List<SupportRoleRow> listActiveSupportRoleRows();

    record SupportRoleRow(Long id, String roleCode) {}

    // Match A1's latest active primary relation; scope supplies its exact normalized dictionary/fallback rules.
    String SUPPORT_OPERATOR_FROM = """
              FROM nx_admin scope_agent
              LEFT JOIN nx_admin_role primary_role ON primary_role.id =
                   (SELECT rr.role_id
                      FROM nx_admin_role_relation rr
                      JOIN nx_admin_role r
                        ON r.id = rr.role_id
                       AND r.status = 1
                       AND r.is_deleted = 0
                     WHERE rr.admin_id = scope_agent.id
                       AND rr.is_deleted = 0
                     ORDER BY rr.updated_at DESC, rr.id DESC
                     LIMIT 1)
            """;

    String SUPPORT_OPERATOR_WHERE = """
             WHERE scope_agent.is_deleted = 0
               AND (
                 <choose>
                   <when test='scope != null and scope.supportRoleIds != null and scope.supportRoleIds.size() > 0'>
                     primary_role.id IN
                     <foreach collection='scope.supportRoleIds' item='roleId' open='(' separator=',' close=')'>#{roleId}</foreach>
                   </when>
                   <otherwise>1=0</otherwise>
                 </choose>
                 <if test='scope != null and scope.superFallbackToSupport'>
                   OR (scope_agent.super_admin = 1 AND (primary_role.id IS NULL
                     <if test='scope.unusablePrimaryRoleIds != null and scope.unusablePrimaryRoleIds.size() > 0'>
                       OR primary_role.id IN
                       <foreach collection='scope.unusablePrimaryRoleIds' item='roleId' open='(' separator=',' close=')'>#{roleId}</foreach>
                     </if>
                   ))
                 </if>
               )
             <if test='scope != null and scope.visibleAdminId != null'>
               AND scope_agent.id = #{scope.visibleAdminId}
             </if>
            """ + SupportGroupMapper.AGENT_SCOPE_PREDICATE;

    @Select("<script>SELECT COUNT(1) " + SUPPORT_OPERATOR_FROM + SUPPORT_OPERATOR_WHERE + "</script>")
    long countSupportOperators(@Param("scope") SupportOperatorScope scope);

    @Select("""
            <script>
            SELECT scope_agent.id AS adminId,
                   COALESCE(NULLIF(TRIM(scope_agent.nickname), ''), NULLIF(TRIM(scope_agent.username), ''), CAST(scope_agent.id AS CHAR)) AS name,
                   COALESCE(TRIM(scope_agent.email), '') AS email,
                   st.avatar_asset_id AS avatarAssetId,
                   COALESCE(st.avatar_version, 0) AS avatarVersion,
                   IF(scope_agent.status=1,'enabled','disabled') AS status
            """ + SUPPORT_OPERATOR_FROM + """
              LEFT JOIN nx_admin_account_state st ON st.admin_id = scope_agent.id AND st.is_deleted = 0
            """ + SUPPORT_OPERATOR_WHERE + """
             ORDER BY scope_agent.id ASC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<SupportOperatorRecord> pageSupportOperators(@Param("scope") SupportOperatorScope scope,
                                                   @Param("limit") long limit,
                                                   @Param("offset") long offset);

    @Select("<script>SELECT scope_agent.id " + SUPPORT_OPERATOR_FROM + SUPPORT_OPERATOR_WHERE + """
        AND
        <choose>
          <when test='directoryIds != null and directoryIds.size() > 0'>
            scope_agent.id IN
            <foreach collection='directoryIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>
          </when>
          <otherwise>1=0</otherwise>
        </choose>
        AND EXISTS (SELECT 1
        """ + SupportBindingMapper.ELIGIBLE_AGENT_FROM + """
            AND a.id=scope_agent.id FOR SHARE)
        ORDER BY scope_agent.id FOR SHARE</script>
        """)
    List<Long> listAssignmentEligibleAgentIds(@Param("directoryIds") List<Long> directoryIds, @Param("scope") SupportOperatorScope scope);

    // One consistent SELECT: never mix current-read eligibility with a historical RR projection.
    @Select("""
        <script>SELECT assignmentId,currentAdvisorId,currentAdvisorName,
               CASE WHEN eligible THEN 'ASSIGNED' ELSE 'ADVISOR_DISABLED' END assignmentState,
               CASE WHEN NOT eligible THEN 'DISABLED' WHEN busy=1 THEN 'BUSY' ELSE 'UNKNOWN' END availability,avatarAssetId,avatarVersion
          FROM (SELECT x.id assignmentId,x.agent_admin_id currentAdvisorId,
                       COALESCE(NULLIF(a.nickname,''),a.username) currentAdvisorName,p.busy,
                       st.avatar_asset_id AS avatarAssetId,COALESCE(st.avatar_version,0) AS avatarVersion,
                       EXISTS(SELECT 1
        """ + SupportBindingMapper.ELIGIBLE_AGENT_BASE_FROM + SupportGroupMapper.UNIQUE_QUALIFICATION_SNAPSHOT + """
                          AND a.id=x.agent_admin_id) eligible
                  FROM nx_support_agent_user_assignment x
                  LEFT JOIN nx_admin a ON a.id=x.agent_admin_id
                  LEFT JOIN nx_support_agent_profile p ON p.admin_id=x.agent_admin_id
                  LEFT JOIN nx_admin_account_state st ON st.admin_id=x.agent_admin_id AND st.is_deleted=0
                 WHERE x.user_id=#{userId} AND x.status='ACTIVE' AND x.is_deleted=0) projection</script>
        """)
    ffdd.opsconsole.content.domain.AppSupportAdvisorView findAppAdvisor(Long userId);

    @Select("SELECT COUNT(*) FROM (SELECT user_id FROM nx_support_agent_user_assignment WHERE status='ACTIVE' AND is_deleted=0 GROUP BY user_id HAVING COUNT(*) > 1) conflicts")
    long countDuplicateActiveCustomers();
    @Select("""
            SELECT a.id adminId,COALESCE(NULLIF(a.nickname,''),a.username) name
              FROM nx_support_agent_user_assignment x JOIN nx_admin a ON a.id=x.agent_admin_id
             WHERE x.user_id=#{userId} AND x.status='ACTIVE' AND x.is_deleted=0
             FOR SHARE
            """)
    DedicatedAdvisorBindingView findActiveDedicatedAdvisor(@Param("userId") Long userId);

    @Select("""
            SELECT DISTINCT a.id AS adminId,
                   COALESCE(NULLIF(TRIM(a.nickname), ''), NULLIF(TRIM(a.username), ''), CAST(a.id AS CHAR)) AS name
              FROM nx_admin a
              JOIN nx_admin_role_relation rr
                ON rr.admin_id = a.id
               AND rr.is_deleted = 0
              JOIN nx_admin_role r
                ON r.id = rr.role_id
               AND r.role_code = 'SUPPORT'
               AND r.status = 1
               AND r.is_deleted = 0
              JOIN nx_support_agent_profile p
                ON p.admin_id = a.id
               AND p.enabled = 1
               AND p.transferable = 1
               AND p.busy = 0
               AND p.is_deleted = 0
             WHERE a.status = 1
               AND a.is_deleted = 0
               AND FIND_IN_SET('support', REPLACE(LOWER(p.service_types), ' ', '')) > 0
             ORDER BY a.id ASC
            """)
    List<SupportTicketAssigneeCandidateView> listTicketAssigneeCandidates();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_support_agent_profile (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              admin_id BIGINT NOT NULL,
              seat_type VARCHAR(32) NOT NULL DEFAULT 'GENERAL',
              position VARCHAR(64) NOT NULL,
              service_types VARCHAR(255) NOT NULL,
              tags VARCHAR(255) NOT NULL,
              max_concurrent INT NOT NULL DEFAULT 10,
              enabled TINYINT NOT NULL DEFAULT 1,
              transferable TINYINT NOT NULL DEFAULT 1,
              busy TINYINT NOT NULL DEFAULT 0,
              version BIGINT NOT NULL DEFAULT 1,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_support_agent_profile_admin (admin_id),
              KEY idx_support_agent_profile_enabled (enabled, is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createProfileTable();

    @Select("""
            SELECT COUNT(1)
              FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'nx_support_agent_profile'
               AND COLUMN_NAME = 'seat_type'
            """)
    long countSeatTypeColumn();

    @Update("""
            ALTER TABLE nx_support_agent_profile
            ADD COLUMN seat_type VARCHAR(32) NOT NULL DEFAULT 'GENERAL' AFTER admin_id
            """)
    int addSeatTypeColumn();

    @Update("""
            UPDATE nx_support_agent_profile
               SET seat_type = CASE
                   WHEN position LIKE '%主管%' THEN 'MANAGER'
                   WHEN position LIKE '%专属%' OR position LIKE '%顾问%' THEN 'DEDICATED'
                   ELSE 'GENERAL'
               END
             WHERE seat_type IS NULL
                OR seat_type = ''
                OR seat_type = 'GENERAL'
            """)
    int backfillSeatType();

    @Select("""
            SELECT COUNT(1) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'nx_support_agent_profile'
               AND COLUMN_NAME = 'version'
            """)
    long countProfileVersionColumn();

    @Update("""
            ALTER TABLE nx_support_agent_profile
            ADD COLUMN version BIGINT NOT NULL DEFAULT 1 AFTER busy
            """)
    int addProfileVersionColumn();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_support_agent_user_assignment (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              agent_admin_id BIGINT NOT NULL,
              user_id BIGINT NOT NULL,
              status VARCHAR(32) NOT NULL,
              starts_at DATETIME NOT NULL,
              ends_at DATETIME DEFAULT NULL,
              operator VARCHAR(64) DEFAULT NULL,
              reason VARCHAR(255) DEFAULT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              KEY idx_support_assignment_agent (agent_admin_id, status, is_deleted),
              KEY idx_support_assignment_user (user_id, status, is_deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createAssignmentTable();

    @Select("""
            SELECT COUNT(1)
              FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'nx_support_agent_user_assignment'
               AND COLUMN_NAME = 'assignment_type'
            """)
    long countAssignmentTypeColumn();

    @Update("""
            ALTER TABLE nx_support_agent_user_assignment
            DROP COLUMN assignment_type
            """)
    int dropAssignmentTypeColumn();

    @Update("""
            UPDATE nx_support_agent_user_assignment stale
              JOIN (
                SELECT id
                  FROM (
                    SELECT id,
                           ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY updated_at DESC, id DESC) AS row_num
                      FROM nx_support_agent_user_assignment
                     WHERE status='ACTIVE'
                       AND is_deleted=0
                  ) ranked
                 WHERE ranked.row_num > 1
              ) duplicate ON duplicate.id=stale.id
               SET stale.status='INACTIVE',
                   stale.ends_at=COALESCE(stale.ends_at, NOW()),
                   stale.reason=COALESCE(NULLIF(stale.reason, ''), 'deduplicate active support assignment'),
                   stale.updated_at=NOW()
            """)
    int deactivateDuplicateActiveAssignments();

    @Select("""
            SELECT COUNT(1)
              FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'nx_support_agent_user_assignment'
               AND COLUMN_NAME = 'active_user_id'
            """)
    long countActiveUserColumn();

    @Update("""
            ALTER TABLE nx_support_agent_user_assignment
            ADD COLUMN active_user_id BIGINT
              GENERATED ALWAYS AS (
                CASE WHEN status = 'ACTIVE' AND is_deleted = 0 THEN user_id ELSE NULL END
              ) STORED
            """)
    int addActiveUserColumn();

    @Select("""
            SELECT COUNT(1)
              FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'nx_support_agent_user_assignment'
               AND INDEX_NAME = 'uq_support_assignment_active_user'
            """)
    long countActiveUserUniqueIndex();

    @Update("""
            ALTER TABLE nx_support_agent_user_assignment
            ADD UNIQUE KEY uq_support_assignment_active_user (active_user_id)
            """)
    int addActiveUserUniqueIndex();

    @Select("""
            <script>
            SELECT admin_id AS adminId,
                   seat_type AS seatType,
                   position,
                   service_types AS serviceTypes,
                   tags,
                   max_concurrent AS maxConcurrent,
                   enabled,
                   transferable,
                   busy,
                   version,
                   DATE_FORMAT(updated_at, '%Y-%m-%dT%H:%i:%s') AS updatedAt
              FROM nx_support_agent_profile
             WHERE is_deleted=0
             <choose>
               <when test='adminIds != null and adminIds.size() > 0'>
                 AND admin_id IN
                 <foreach collection='adminIds' item='adminId' open='(' separator=',' close=')'>
                   #{adminId}
                 </foreach>
               </when>
               <otherwise>
                 AND 1=0
               </otherwise>
             </choose>
             ORDER BY admin_id ASC
            </script>
            """)
    List<SupportAgentProfileRow> listProfiles(@Param("adminIds") List<Long> adminIds);

    @Select("""
            SELECT admin_id AS adminId,
                   seat_type AS seatType,
                   position,
                   service_types AS serviceTypes,
                   tags,
                   max_concurrent AS maxConcurrent,
                   enabled,
                   transferable,
                   busy,
                   version,
                   DATE_FORMAT(updated_at, '%Y-%m-%dT%H:%i:%s') AS updatedAt
              FROM nx_support_agent_profile
             WHERE is_deleted=0 AND admin_id=#{adminId}
             LIMIT 1
            """)
    SupportAgentProfileRow findProfile(@Param("adminId") Long adminId);

    @Insert("""
            INSERT INTO nx_support_agent_profile (
              admin_id, seat_type, position, service_types, tags, max_concurrent, enabled, transferable, busy,
              created_at, updated_at, is_deleted
            ) VALUES (
              #{adminId}, #{seatType}, #{position}, #{serviceTypes}, #{tags}, #{maxConcurrent}, 1, 1, 0,
              #{now}, #{now}, 0
            )
            ON DUPLICATE KEY UPDATE
              is_deleted=0,
              updated_at=updated_at
            """)
    int ensureDefaultProfile(@Param("adminId") Long adminId,
                             @Param("seatType") String seatType,
                             @Param("position") String position,
                             @Param("serviceTypes") String serviceTypes,
                             @Param("tags") String tags,
                             @Param("maxConcurrent") int maxConcurrent,
                             @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_support_agent_profile
               SET seat_type=#{seatType},
                   position=#{position},
                   service_types=#{serviceTypes},
                   tags=#{tags},
                   max_concurrent=#{maxConcurrent},
                   enabled=#{enabled},
                   transferable=#{transferable},
                   busy=#{busy},
                   updated_at=#{now},
                   is_deleted=0
             WHERE admin_id=#{adminId}
            """)
    int updateProfile(@Param("adminId") Long adminId,
                      @Param("seatType") String seatType,
                      @Param("position") String position,
                      @Param("serviceTypes") String serviceTypes,
                      @Param("tags") String tags,
                      @Param("maxConcurrent") int maxConcurrent,
                      @Param("enabled") int enabled,
                      @Param("transferable") int transferable,
                      @Param("busy") int busy,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_support_agent_profile
               SET seat_type=#{seatType}, position=#{position}, service_types=#{serviceTypes}, tags=#{tags},
                   max_concurrent=#{maxConcurrent}, enabled=#{enabled}, transferable=#{transferable}, busy=#{busy},
                   version=version+1, updated_at=#{now}, is_deleted=0
             WHERE admin_id=#{adminId} AND version=#{expectedVersion} AND is_deleted=0
            """)
    int updateProfileCas(@Param("adminId") Long adminId,
                         @Param("seatType") String seatType,
                         @Param("position") String position,
                         @Param("serviceTypes") String serviceTypes,
                         @Param("tags") String tags,
                         @Param("maxConcurrent") int maxConcurrent,
                         @Param("enabled") int enabled,
                         @Param("transferable") int transferable,
                         @Param("busy") int busy,
                         @Param("expectedVersion") long expectedVersion,
                         @Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(1)
              FROM nx_support_agent_user_assignment
             WHERE agent_admin_id=#{agentAdminId}
               AND status='ACTIVE'
               AND is_deleted=0
            """)
    long countActiveAssignments(@Param("agentAdminId") Long agentAdminId);

    @Select("<script>SELECT COUNT(*) FROM nx_support_agent_user_assignment assignment "
            + "JOIN nx_user scope_customer ON scope_customer.id=assignment.user_id "
            + "WHERE assignment.agent_admin_id=#{agentAdminId} AND assignment.status='ACTIVE' AND assignment.is_deleted=0 "
            + "AND assignment.ends_at IS NULL AND assignment.starts_at &lt;= UTC_TIMESTAMP(6) "
            + SupportBindingMapper.CUSTOMER_SCOPE_PREDICATE + "</script>")
    long countScopedActiveAssignments(@Param("agentAdminId") Long agentAdminId,
                                      @Param("scope") ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope scope);

    @Select("""
            SELECT COUNT(1)
              FROM nx_user
             WHERE id=#{userId}
               AND is_deleted=0
            """)
    long countActiveUser(@Param("userId") Long userId);

    @Select("""
            <script>
            SELECT id
              FROM nx_user
             WHERE is_deleted=0
               AND id IN
               <foreach collection='userIds' item='userId' open='(' separator=',' close=')'>
                 #{userId}
               </foreach>
            </script>
            """)
    List<Long> listActiveUserIds(@Param("userIds") List<Long> userIds);

    @Select("""
            <script>
            SELECT a.id,
                   a.agent_admin_id AS agentAdminId,
                   a.user_id AS userId,
                   CONCAT('U', LPAD(a.user_id, GREATEST(8, LENGTH(CAST(a.user_id AS CHAR))), '0')) AS userNo,
                   COALESCE(NULLIF(u.nickname, ''), CONCAT('用户', a.user_id)) AS nickname,
                   a.status,
                   DATE_FORMAT(a.starts_at, '%Y-%m-%dT%H:%i:%s') AS startsAt,
                   DATE_FORMAT(a.ends_at, '%Y-%m-%dT%H:%i:%s') AS endsAt,
                   a.operator,
                   a.reason,
                   DATE_FORMAT(a.updated_at, '%Y-%m-%dT%H:%i:%s') AS updatedAt
              FROM nx_support_agent_user_assignment a
              JOIN nx_user u ON u.id=a.user_id AND u.is_deleted=0
             WHERE a.is_deleted=0
               AND a.status='ACTIVE'
             <choose>
               <when test='agentAdminIds != null and agentAdminIds.size() > 0'>
                 AND a.agent_admin_id IN
                 <foreach collection='agentAdminIds' item='agentAdminId' open='(' separator=',' close=')'>
                   #{agentAdminId}
                 </foreach>
               </when>
               <otherwise>
                 AND 1=0
               </otherwise>
             </choose>
             ORDER BY a.updated_at DESC, a.id DESC
            </script>
            """)
    List<SupportAgentAssignmentView> listActiveAssignments(@Param("agentAdminIds") List<Long> agentAdminIds);

    @Select("""
            <script>
            SELECT a.id,
                   a.agent_admin_id AS agentAdminId,
                   a.user_id AS userId,
                   CONCAT('U', LPAD(a.user_id, GREATEST(8, LENGTH(CAST(a.user_id AS CHAR))), '0')) AS userNo,
                   COALESCE(NULLIF(u.nickname, ''), CONCAT('用户', a.user_id)) AS nickname,
                   a.status,
                   DATE_FORMAT(a.starts_at, '%Y-%m-%dT%H:%i:%s') AS startsAt,
                   DATE_FORMAT(a.ends_at, '%Y-%m-%dT%H:%i:%s') AS endsAt,
                   a.operator,
                   a.reason,
                   DATE_FORMAT(a.updated_at, '%Y-%m-%dT%H:%i:%s') AS updatedAt
              FROM nx_support_agent_user_assignment a
              JOIN nx_user u ON u.id=a.user_id AND u.is_deleted=0
              JOIN nx_user scope_customer ON scope_customer.id=a.user_id
             WHERE a.is_deleted=0
               AND a.status='ACTIVE' AND a.ends_at IS NULL AND a.starts_at &lt;= UTC_TIMESTAMP(6)
            """ + SupportBindingMapper.CUSTOMER_SCOPE_PREDICATE + """
             <choose>
               <when test='agentAdminIds != null and agentAdminIds.size() > 0'>
                 AND a.agent_admin_id IN
                 <foreach collection='agentAdminIds' item='agentAdminId' open='(' separator=',' close=')'>
                   #{agentAdminId}
                 </foreach>
               </when>
               <otherwise>
                 AND 1=0
               </otherwise>
             </choose>
             ORDER BY a.updated_at DESC, a.id DESC
            </script>
            """)
    List<SupportAgentAssignmentView> listScopedActiveAssignments(@Param("agentAdminIds") List<Long> agentAdminIds, @Param("scope") ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope scope);

    @Update("""
            UPDATE nx_support_agent_user_assignment
               SET status='INACTIVE',
                   ends_at=#{now},
                   operator=#{operator},
                   reason=#{reason},
                   updated_at=#{now}
             WHERE user_id=#{userId}
               AND status='ACTIVE'
               AND is_deleted=0
            """)
    int deactivateActiveAssignmentsForUser(@Param("userId") Long userId,
                                           @Param("operator") String operator,
                                           @Param("reason") String reason,
                                           @Param("now") LocalDateTime now);

    @Update("""
            <script>
            UPDATE nx_support_agent_user_assignment
               SET status='INACTIVE',
                   ends_at=#{now},
                   operator=#{operator},
                   reason=#{reason},
                   updated_at=#{now}
             WHERE status='ACTIVE'
               AND is_deleted=0
               AND user_id IN
               <foreach collection='userIds' item='userId' open='(' separator=',' close=')'>
                 #{userId}
               </foreach>
            </script>
            """)
    int deactivateActiveAssignmentsForUsers(@Param("userIds") List<Long> userIds,
                                             @Param("operator") String operator,
                                             @Param("reason") String reason,
                                             @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO nx_support_agent_user_assignment (
              agent_admin_id, user_id, status, starts_at, operator, reason,
              created_at, updated_at, is_deleted
            ) VALUES (
              #{agentAdminId}, #{userId}, 'ACTIVE', #{now}, #{operator}, #{reason},
              #{now}, #{now}, 0
            )
            """)
    int insertAssignment(@Param("agentAdminId") Long agentAdminId,
                         @Param("userId") Long userId,
                         @Param("operator") String operator,
                         @Param("reason") String reason,
                         @Param("now") LocalDateTime now);

    @Insert("""
            <script>
            INSERT INTO nx_support_agent_user_assignment (
              agent_admin_id, user_id, status, starts_at, operator, reason,
              created_at, updated_at, is_deleted
            ) VALUES
            <foreach collection='userIds' item='userId' separator=','>
              (#{agentAdminId}, #{userId}, 'ACTIVE', #{now}, #{operator}, #{reason},
               #{now}, #{now}, 0)
            </foreach>
            </script>
            """)
    int insertAssignments(@Param("agentAdminId") Long agentAdminId,
                          @Param("userIds") List<Long> userIds,
                          @Param("operator") String operator,
                          @Param("reason") String reason,
                          @Param("now") LocalDateTime now);

    @Select("""
            SELECT a.id,
                   a.agent_admin_id AS agentAdminId,
                   a.user_id AS userId,
                   CONCAT('U', LPAD(a.user_id, GREATEST(8, LENGTH(CAST(a.user_id AS CHAR))), '0')) AS userNo,
                   COALESCE(NULLIF(u.nickname, ''), CONCAT('用户', a.user_id)) AS nickname,
                   a.status,
                   DATE_FORMAT(a.starts_at, '%Y-%m-%dT%H:%i:%s') AS startsAt,
                   DATE_FORMAT(a.ends_at, '%Y-%m-%dT%H:%i:%s') AS endsAt,
                   a.operator,
                   a.reason,
                   DATE_FORMAT(a.updated_at, '%Y-%m-%dT%H:%i:%s') AS updatedAt
             FROM nx_support_agent_user_assignment a
              JOIN nx_user u ON u.id=a.user_id AND u.is_deleted=0
             WHERE a.agent_admin_id=#{agentAdminId}
               AND a.user_id=#{userId}
               AND a.status='ACTIVE'
               AND a.is_deleted=0
             ORDER BY a.id DESC
             LIMIT 1
            """)
    SupportAgentAssignmentView findActiveAssignment(@Param("agentAdminId") Long agentAdminId,
                                                    @Param("userId") Long userId);

    @Select("""
            <script>
            SELECT a.id,
                   a.agent_admin_id AS agentAdminId,
                   a.user_id AS userId,
                   CONCAT('U', LPAD(a.user_id, GREATEST(8, LENGTH(CAST(a.user_id AS CHAR))), '0')) AS userNo,
                   COALESCE(NULLIF(u.nickname, ''), CONCAT('用户', a.user_id)) AS nickname,
                   a.status,
                   DATE_FORMAT(a.starts_at, '%Y-%m-%dT%H:%i:%s') AS startsAt,
                   DATE_FORMAT(a.ends_at, '%Y-%m-%dT%H:%i:%s') AS endsAt,
                   a.operator,
                   a.reason,
                   DATE_FORMAT(a.updated_at, '%Y-%m-%dT%H:%i:%s') AS updatedAt
              FROM nx_support_agent_user_assignment a
              JOIN nx_user u ON u.id=a.user_id AND u.is_deleted=0
             WHERE a.agent_admin_id=#{agentAdminId}
               AND a.status='ACTIVE'
               AND a.is_deleted=0
               AND a.user_id IN
               <foreach collection='userIds' item='userId' open='(' separator=',' close=')'>
                 #{userId}
               </foreach>
            </script>
            """)
    List<SupportAgentAssignmentView> listActiveAssignmentsForUsers(
            @Param("agentAdminId") Long agentAdminId,
            @Param("userIds") List<Long> userIds);

    @Select("""
            SELECT a.id,
                   a.agent_admin_id AS agentAdminId,
                   a.user_id AS userId,
                   CONCAT('U', LPAD(a.user_id, GREATEST(8, LENGTH(CAST(a.user_id AS CHAR))), '0')) AS userNo,
                   COALESCE(NULLIF(u.nickname, ''), CONCAT('用户', a.user_id)) AS nickname,
                   a.status,
                   DATE_FORMAT(a.starts_at, '%Y-%m-%dT%H:%i:%s') AS startsAt,
                   DATE_FORMAT(a.ends_at, '%Y-%m-%dT%H:%i:%s') AS endsAt,
                   a.operator,
                   a.reason,
                   DATE_FORMAT(a.updated_at, '%Y-%m-%dT%H:%i:%s') AS updatedAt
              FROM nx_support_agent_user_assignment a
              JOIN nx_user u ON u.id=a.user_id AND u.is_deleted=0
             WHERE a.agent_admin_id=#{agentAdminId}
               AND a.id=#{assignmentId}
               AND a.is_deleted=0
             LIMIT 1
            """)
    SupportAgentAssignmentView findAssignmentById(@Param("agentAdminId") Long agentAdminId,
                                                  @Param("assignmentId") Long assignmentId);

    @Update("""
            UPDATE nx_support_agent_user_assignment
               SET status='INACTIVE',
                   ends_at=#{now},
                   operator=#{operator},
                   reason=#{reason},
                   updated_at=#{now}
             WHERE id=#{assignmentId}
               AND agent_admin_id=#{agentAdminId}
               AND status='ACTIVE'
               AND is_deleted=0
            """)
    int deactivateAssignment(@Param("agentAdminId") Long agentAdminId,
                             @Param("assignmentId") Long assignmentId,
                             @Param("operator") String operator,
                             @Param("reason") String reason,
                             @Param("now") LocalDateTime now);

    record SupportAgentProfileRow(
            Long adminId,
            String seatType,
            String position,
            String serviceTypes,
            String tags,
            Integer maxConcurrent,
            Integer enabled,
            Integer transferable,
            Integer busy,
            Long version,
            String updatedAt) {
    }
}
