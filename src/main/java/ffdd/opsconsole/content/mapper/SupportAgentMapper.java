package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.domain.SupportAgentAssignmentView;
import ffdd.opsconsole.content.infrastructure.SupportAgentProfileEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface SupportAgentMapper extends BaseMapper<SupportAgentProfileEntity> {
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

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_support_agent_user_assignment (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              agent_admin_id BIGINT NOT NULL,
              user_id BIGINT NOT NULL,
              assignment_type VARCHAR(32) NOT NULL,
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

    @Select("""
            SELECT COUNT(1)
              FROM nx_support_agent_user_assignment
             WHERE agent_admin_id=#{agentAdminId}
               AND status='ACTIVE'
               AND is_deleted=0
            """)
    long countActiveAssignments(@Param("agentAdminId") Long agentAdminId);

    @Select("""
            SELECT COUNT(1)
              FROM nx_user
             WHERE id=#{userId}
               AND is_deleted=0
            """)
    long countActiveUser(@Param("userId") Long userId);

    @Select("""
            <script>
            SELECT a.id,
                   a.agent_admin_id AS agentAdminId,
                   a.user_id AS userId,
                   CONCAT('U', LPAD(a.user_id, 8, '0')) AS userNo,
                   COALESCE(NULLIF(u.nickname, ''), CONCAT('用户', a.user_id)) AS nickname,
                   a.assignment_type AS assignmentType,
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

    @Update("""
            UPDATE nx_support_agent_user_assignment
               SET status='INACTIVE',
                   ends_at=#{now},
                   operator=#{operator},
                   reason=#{reason},
                   updated_at=#{now}
             WHERE agent_admin_id=#{agentAdminId}
               AND user_id=#{userId}
               AND assignment_type=#{assignmentType}
               AND status='ACTIVE'
               AND is_deleted=0
            """)
    int deactivateSameAssignment(@Param("agentAdminId") Long agentAdminId,
                                 @Param("userId") Long userId,
                                 @Param("assignmentType") String assignmentType,
                                 @Param("operator") String operator,
                                 @Param("reason") String reason,
                                 @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO nx_support_agent_user_assignment (
              agent_admin_id, user_id, assignment_type, status, starts_at, operator, reason,
              created_at, updated_at, is_deleted
            ) VALUES (
              #{agentAdminId}, #{userId}, #{assignmentType}, 'ACTIVE', #{now}, #{operator}, #{reason},
              #{now}, #{now}, 0
            )
            """)
    int insertAssignment(@Param("agentAdminId") Long agentAdminId,
                         @Param("userId") Long userId,
                         @Param("assignmentType") String assignmentType,
                         @Param("operator") String operator,
                         @Param("reason") String reason,
                         @Param("now") LocalDateTime now);

    @Select("""
            SELECT a.id,
                   a.agent_admin_id AS agentAdminId,
                   a.user_id AS userId,
                   CONCAT('U', LPAD(a.user_id, 8, '0')) AS userNo,
                   COALESCE(NULLIF(u.nickname, ''), CONCAT('用户', a.user_id)) AS nickname,
                   a.assignment_type AS assignmentType,
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
               AND a.assignment_type=#{assignmentType}
               AND a.status='ACTIVE'
               AND a.is_deleted=0
             ORDER BY a.id DESC
             LIMIT 1
            """)
    SupportAgentAssignmentView findActiveAssignment(@Param("agentAdminId") Long agentAdminId,
                                                    @Param("userId") Long userId,
                                                    @Param("assignmentType") String assignmentType);

    @Select("""
            SELECT a.id,
                   a.agent_admin_id AS agentAdminId,
                   a.user_id AS userId,
                   CONCAT('U', LPAD(a.user_id, 8, '0')) AS userNo,
                   COALESCE(NULLIF(u.nickname, ''), CONCAT('用户', a.user_id)) AS nickname,
                   a.assignment_type AS assignmentType,
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
            String updatedAt) {
    }
}
