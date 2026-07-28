package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.domain.ConversationIdleCandidate;
import ffdd.opsconsole.content.domain.ConversationTimeoutPolicy;
import ffdd.opsconsole.content.infrastructure.ConversationEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ConversationTimeoutPolicyMapper extends BaseMapper<ConversationEntity> {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_conversation_timeout_policy (
              policy_key VARCHAR(64) PRIMARY KEY,
              warn_minutes INT NOT NULL,
              close_minutes INT NOT NULL,
              version BIGINT NOT NULL DEFAULT 1,
              updated_by VARCHAR(64) NOT NULL,
              reason VARCHAR(500) NOT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void ensurePolicyTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_conversation_timeout_event (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              conversation_no VARCHAR(40) NOT NULL,
              event_type VARCHAR(16) NOT NULL,
              activity_at DATETIME NOT NULL,
              policy_version BIGINT NOT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              UNIQUE KEY uk_conversation_timeout_event (conversation_no,event_type,activity_at),
              KEY idx_conversation_timeout_event_time (event_type,created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void ensureEventTable();

    @Insert("""
            INSERT IGNORE INTO nx_conversation_timeout_policy
              (policy_key,warn_minutes,close_minutes,version,updated_by,reason,created_at,updated_at)
            VALUES ('GLOBAL',1,5,1,'system','系统默认会话闲置策略',NOW(),NOW())
            """)
    int insertDefaultPolicy();

    @Select("""
            SELECT policy_key AS policyKey,
                   warn_minutes AS warnMinutes,
                   close_minutes AS closeMinutes,
                   version,
                   updated_by AS updatedBy,
                   reason,
                   updated_at AS updatedAt
              FROM nx_conversation_timeout_policy
             WHERE policy_key='GLOBAL'
             LIMIT 1
            """)
    ConversationTimeoutPolicy selectPolicy();

    @Select("""
            SELECT policy_key AS policyKey,
                   warn_minutes AS warnMinutes,
                   close_minutes AS closeMinutes,
                   version,
                   updated_by AS updatedBy,
                   reason,
                   updated_at AS updatedAt
              FROM nx_conversation_timeout_policy
             WHERE policy_key='GLOBAL'
             LIMIT 1
             FOR UPDATE
            """)
    ConversationTimeoutPolicy selectPolicyForUpdate();

    @Update("""
            UPDATE nx_conversation_timeout_policy
               SET warn_minutes=#{warnMinutes},
                   close_minutes=#{closeMinutes},
                   version=version+1,
                   updated_by=#{operator},
                   reason=#{reason},
                   updated_at=#{now}
             WHERE policy_key='GLOBAL'
               AND version=#{expectedVersion}
            """)
    int updatePolicy(
            @Param("warnMinutes") Integer warnMinutes,
            @Param("closeMinutes") Integer closeMinutes,
            @Param("expectedVersion") Long expectedVersion,
            @Param("operator") String operator,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now);

    @Select("""
            SELECT c.id,
                   c.conversation_no AS conversationNo,
                   c.status,
                   COALESCE(c.last_message_at,c.created_at) AS lastActivityAt
              FROM nx_conversation c
             WHERE c.is_deleted=0
               AND c.status='OPEN'
               AND COALESCE(c.last_message_at,c.created_at) <= #{warnCutoff}
               AND COALESCE(c.last_message_at,c.created_at) > #{closeCutoff}
               AND NOT EXISTS (
                   SELECT 1
                     FROM nx_conversation_timeout_event e
                    WHERE e.conversation_no=c.conversation_no
                      AND e.event_type='WARN'
                      AND e.activity_at=COALESCE(c.last_message_at,c.created_at)
               )
             ORDER BY COALESCE(c.last_message_at,c.created_at) ASC
             LIMIT #{limit}
            """)
    List<ConversationIdleCandidate> selectDueWarningCandidates(
            @Param("warnCutoff") LocalDateTime warnCutoff,
            @Param("closeCutoff") LocalDateTime closeCutoff,
            @Param("limit") int limit);

    @Select("""
            SELECT c.id,
                   c.conversation_no AS conversationNo,
                   c.status,
                   COALESCE(c.last_message_at,c.created_at) AS lastActivityAt
              FROM nx_conversation c
             WHERE c.is_deleted=0
               AND c.status='OPEN'
               AND COALESCE(c.last_message_at,c.created_at) <= #{closeCutoff}
               AND NOT EXISTS (
                   SELECT 1
                     FROM nx_conversation_timeout_event e
                    WHERE e.conversation_no=c.conversation_no
                      AND e.event_type='CLOSE'
                      AND e.activity_at=COALESCE(c.last_message_at,c.created_at)
               )
             ORDER BY COALESCE(c.last_message_at,c.created_at) ASC
             LIMIT #{limit}
            """)
    List<ConversationIdleCandidate> selectDueCloseCandidates(
            @Param("closeCutoff") LocalDateTime closeCutoff,
            @Param("limit") int limit);

    @Select("""
            SELECT c.id,
                   c.conversation_no AS conversationNo,
                   c.status,
                   COALESCE(c.last_message_at,c.created_at) AS lastActivityAt
              FROM nx_conversation c
             WHERE c.conversation_no=#{conversationNo}
               AND c.is_deleted=0
             LIMIT 1
             FOR UPDATE
            """)
    ConversationIdleCandidate lockCandidate(@Param("conversationNo") String conversationNo);

    @Insert("""
            INSERT IGNORE INTO nx_conversation_timeout_event
              (conversation_no,event_type,activity_at,policy_version,created_at)
            VALUES (#{conversationNo},#{eventType},#{activityAt},#{policyVersion},#{now})
            """)
    int insertEvent(
            @Param("conversationNo") String conversationNo,
            @Param("eventType") String eventType,
            @Param("activityAt") LocalDateTime activityAt,
            @Param("policyVersion") Long policyVersion,
            @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO nx_conversation_message
              (conversation_id,conversation_no,sender_id,sender_type,sender_name,content,created_at,updated_at,is_deleted)
            VALUES (#{conversationId},#{conversationNo},NULL,'system','系统',#{content},#{now},#{now},0)
            """)
    int insertSystemMessage(
            @Param("conversationId") Long conversationId,
            @Param("conversationNo") String conversationNo,
            @Param("content") String content,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_conversation
               SET status='CLOSED',
                   last_message=#{message},
                   last_message_at=#{now},
                   version=version+1,
                   updated_at=#{now}
             WHERE conversation_no=#{conversationNo}
               AND status='OPEN'
               AND is_deleted=0
               AND COALESCE(last_message_at,created_at)=#{expectedActivityAt}
            """)
    int closeIfStillIdle(
            @Param("conversationNo") String conversationNo,
            @Param("expectedActivityAt") LocalDateTime expectedActivityAt,
            @Param("message") String message,
            @Param("now") LocalDateTime now);
}
