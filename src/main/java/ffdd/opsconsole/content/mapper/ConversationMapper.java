package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.infrastructure.ConversationEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ConversationMapper extends BaseMapper<ConversationEntity> {
    @Select("SELECT COUNT(*) FROM nx_conversation WHERE is_deleted=0 AND status='OPEN'")
    long countOpen();

    @Select("SELECT COUNT(*) FROM nx_conversation WHERE is_deleted=0 AND status='TRANSFERRED'")
    long countIncomingPending();

    @Select("SELECT COUNT(*) FROM nx_conversation WHERE is_deleted=0 AND unread_count>0 AND status<>'CLOSED'")
    long countUnread();

    @Select("SELECT COUNT(*) FROM nx_conversation WHERE is_deleted=0 AND status='RESOLVED'")
    long countResolved();

    @Select("SELECT COUNT(*) FROM nx_conversation WHERE is_deleted=0 AND status='CLOSED'")
    long countClosed();

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM nx_conversation c
             WHERE c.is_deleted=0
             <if test='status != null and status != ""'>AND c.status=UPPER(#{status})</if>
             <if test='type != null and type != ""'>AND c.conversation_type=LOWER(#{type})</if>
             <if test='ownerAgentId != null and ownerAgentId != ""'>AND c.owner_agent_id=#{ownerAgentId}</if>
             <if test='userId != null'>AND c.user_id=#{userId}</if>
             <if test='keyword != null and keyword != ""'>
               AND (c.conversation_no LIKE CONCAT('%', #{keyword}, '%')
                    OR c.owner_agent_name LIKE CONCAT('%', #{keyword}, '%')
                    OR c.last_message LIKE CONCAT('%', #{keyword}, '%'))
             </if>
             <if test='unreadOnly != null and unreadOnly'>AND c.unread_count &gt; 0 AND c.status &lt;&gt; 'CLOSED'</if>
            </script>
            """)
    long countConversations(@Param("status") String status, @Param("type") String type,
                            @Param("ownerAgentId") String ownerAgentId, @Param("userId") Long userId,
                            @Param("keyword") String keyword, @Param("unreadOnly") Boolean unreadOnly);

    @Select("""
            <script>
            SELECT
              c.id,
              c.conversation_no AS conversationNo,
              c.user_id AS userId,
              c.conversation_type AS conversationType,
              c.status,
              c.owner_agent_id AS ownerAgentId,
              c.owner_agent_name AS ownerAgentName,
              c.unread_count AS unreadCount,
              c.last_message AS lastMessage,
              c.last_message_at AS lastMessageAt,
              t.from_agent_id AS transferFromAgentId,
              t.from_agent_name AS transferFromAgentName,
              t.to_type AS transferToType,
              t.to_id AS transferToId,
              t.to_name AS transferToName,
              t.reason AS transferReason,
              t.transferred_at AS transferredAt,
              c.updated_at AS updatedAt
            FROM nx_conversation c
            LEFT JOIN nx_conversation_transfer t
              ON t.conversation_no=c.conversation_no AND t.status='PENDING' AND t.is_deleted=0
            WHERE c.is_deleted=0
             <if test='status != null and status != ""'>AND c.status=UPPER(#{status})</if>
             <if test='type != null and type != ""'>AND c.conversation_type=LOWER(#{type})</if>
             <if test='ownerAgentId != null and ownerAgentId != ""'>AND c.owner_agent_id=#{ownerAgentId}</if>
             <if test='userId != null'>AND c.user_id=#{userId}</if>
             <if test='keyword != null and keyword != ""'>
               AND (c.conversation_no LIKE CONCAT('%', #{keyword}, '%')
                    OR c.owner_agent_name LIKE CONCAT('%', #{keyword}, '%')
                    OR c.last_message LIKE CONCAT('%', #{keyword}, '%'))
             </if>
             <if test='unreadOnly != null and unreadOnly'>AND c.unread_count &gt; 0 AND c.status &lt;&gt; 'CLOSED'</if>
            ORDER BY COALESCE(c.last_message_at,c.updated_at,c.created_at) DESC
            LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    List<ContentConversationView> pageConversations(@Param("status") String status, @Param("type") String type,
                                                    @Param("ownerAgentId") String ownerAgentId, @Param("keyword") String keyword,
                                                    @Param("userId") Long userId, @Param("unreadOnly") Boolean unreadOnly,
                                                    @Param("pageSize") long pageSize,
                                                    @Param("offset") long offset);

    @Select("""
            SELECT
              c.id,
              c.conversation_no AS conversationNo,
              c.user_id AS userId,
              c.conversation_type AS conversationType,
              c.status,
              c.owner_agent_id AS ownerAgentId,
              c.owner_agent_name AS ownerAgentName,
              c.unread_count AS unreadCount,
              c.last_message AS lastMessage,
              c.last_message_at AS lastMessageAt,
              t.from_agent_id AS transferFromAgentId,
              t.from_agent_name AS transferFromAgentName,
              t.to_type AS transferToType,
              t.to_id AS transferToId,
              t.to_name AS transferToName,
              t.reason AS transferReason,
              t.transferred_at AS transferredAt,
              c.updated_at AS updatedAt
            FROM nx_conversation c
            LEFT JOIN nx_conversation_transfer t
              ON t.conversation_no=c.conversation_no AND t.status='PENDING' AND t.is_deleted=0
            WHERE c.is_deleted=0 AND c.conversation_no=#{conversationNo}
            LIMIT 1
            """)
    ContentConversationView findByConversationNo(@Param("conversationNo") String conversationNo);

    @Select("""
            SELECT
              c.id,
              c.conversation_no AS conversationNo,
              c.user_id AS userId,
              c.conversation_type AS conversationType,
              c.status,
              c.owner_agent_id AS ownerAgentId,
              c.owner_agent_name AS ownerAgentName,
              c.unread_count AS unreadCount,
              c.last_message AS lastMessage,
              c.last_message_at AS lastMessageAt,
              t.from_agent_id AS transferFromAgentId,
              t.from_agent_name AS transferFromAgentName,
              t.to_type AS transferToType,
              t.to_id AS transferToId,
              t.to_name AS transferToName,
              t.reason AS transferReason,
              t.transferred_at AS transferredAt,
              c.updated_at AS updatedAt
            FROM nx_conversation c
            JOIN nx_conversation_transfer t
              ON t.conversation_no=c.conversation_no AND t.status='PENDING' AND t.is_deleted=0
            WHERE c.is_deleted=0
              AND c.status='TRANSFERRED'
              AND t.transferred_at <= #{cutoff}
              AND COALESCE(t.to_type,'') <> 'standby'
            ORDER BY t.transferred_at ASC
            LIMIT #{limit}
            """)
    List<ContentConversationView> overdueTransferredConversations(@Param("cutoff") LocalDateTime cutoff,
                                                                  @Param("limit") int limit);

    @Update("""
            UPDATE nx_conversation
               SET status='TRANSFERRED', owner_agent_id=#{targetId}, owner_agent_name=#{targetName}, updated_at=#{now}
             WHERE conversation_no=#{conversationNo} AND is_deleted=0
            """)
    int markTransferred(@Param("conversationNo") String conversationNo, @Param("targetId") String targetId,
                        @Param("targetName") String targetName, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_conversation
               SET status='TRANSFERRED',
                   owner_agent_id=#{targetId},
                   owner_agent_name=#{targetName},
                   last_message=CONCAT('Transferred to ', #{targetName}),
                   last_message_at=#{now},
                   updated_at=#{now}
             WHERE conversation_no=#{conversationNo} AND is_deleted=0
            """)
    int fallbackConversation(@Param("conversationNo") String conversationNo, @Param("targetId") String targetId,
                             @Param("targetName") String targetName, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO nx_conversation_transfer
              (conversation_no,from_agent_id,from_agent_name,to_type,to_id,to_name,reason,status,operator,transferred_at,is_deleted,created_at,updated_at)
            VALUES (#{conversationNo},#{fromAgentId},#{fromAgentName},#{targetType},#{targetId},#{targetName},#{reason},'PENDING',#{operator},#{now},0,#{now},#{now})
            """)
    int insertTransfer(@Param("conversationNo") String conversationNo, @Param("fromAgentId") String fromAgentId,
                       @Param("fromAgentName") String fromAgentName, @Param("targetType") String targetType,
                       @Param("targetId") String targetId, @Param("targetName") String targetName,
                       @Param("reason") String reason, @Param("operator") String operator,
                       @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_conversation
               SET status='OPEN', owner_agent_id=#{operator}, owner_agent_name=#{operator}, updated_at=#{now}
             WHERE conversation_no=#{conversationNo} AND is_deleted=0
            """)
    int acceptConversation(@Param("conversationNo") String conversationNo, @Param("operator") String operator,
                           @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_conversation_transfer
               SET status='ACCEPTED', accepted_by=#{operator}, accepted_at=#{now}, updated_at=#{now}
             WHERE conversation_no=#{conversationNo} AND status='PENDING' AND is_deleted=0
            """)
    int markTransferAccepted(@Param("conversationNo") String conversationNo, @Param("operator") String operator,
                             @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_conversation
               SET status='OPEN', owner_agent_id=#{fromAgentId}, owner_agent_name=#{fromAgentName}, updated_at=#{now}
             WHERE conversation_no=#{conversationNo} AND is_deleted=0
            """)
    int returnConversation(@Param("conversationNo") String conversationNo, @Param("fromAgentId") String fromAgentId,
                           @Param("fromAgentName") String fromAgentName, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_conversation_transfer
               SET status='RETURNED', return_reason=#{reason}, returned_by=#{operator}, returned_at=#{now}, updated_at=#{now}
             WHERE conversation_no=#{conversationNo} AND status='PENDING' AND is_deleted=0
            """)
    int markTransferReturned(@Param("conversationNo") String conversationNo, @Param("reason") String reason,
                             @Param("operator") String operator, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_conversation
               SET last_message=#{message},
                   last_message_at=#{now},
                   updated_at=#{now}
             WHERE conversation_no=#{conversationNo} AND is_deleted=0
            """)
    int markTransferWait(@Param("conversationNo") String conversationNo, @Param("message") String message,
                         @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_conversation_transfer
               SET to_type='standby',
                   to_id=#{targetId},
                   to_name=#{targetName},
                   fallback_reason=#{reason},
                   fallback_by=#{operator},
                   fallback_at=#{now},
                   updated_at=#{now}
             WHERE conversation_no=#{conversationNo} AND status='PENDING' AND is_deleted=0
               AND COALESCE(to_type,'') <> 'standby'
               AND fallback_at IS NULL
            """)
    int markTransferFallback(@Param("conversationNo") String conversationNo, @Param("targetId") String targetId,
                             @Param("targetName") String targetName, @Param("reason") String reason,
                             @Param("operator") String operator, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_conversation
               SET status=CASE WHEN status='RESOLVED' THEN 'OPEN' ELSE status END,
                   unread_count=0,
                   last_message=#{body},
                   last_message_at=#{now},
                   updated_at=#{now}
             WHERE conversation_no=#{conversationNo} AND is_deleted=0
            """)
    int replyConversation(@Param("conversationNo") String conversationNo, @Param("body") String body,
                          @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_conversation
               SET status=#{status}, updated_at=#{now}
             WHERE conversation_no=#{conversationNo} AND is_deleted=0
            """)
    int updateConversationStatus(@Param("conversationNo") String conversationNo, @Param("status") String status,
                                 @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_conversation
               SET status='RESOLVED',
                   last_message=#{message},
                   last_message_at=#{now},
                   updated_at=#{now}
             WHERE conversation_no=#{conversationNo} AND is_deleted=0
            """)
    int markConvertedToTicket(@Param("conversationNo") String conversationNo, @Param("message") String message,
                              @Param("now") LocalDateTime now);
}
