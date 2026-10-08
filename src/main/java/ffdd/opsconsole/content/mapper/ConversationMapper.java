package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.infrastructure.ConversationEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import org.apache.ibatis.annotations.Update;

public interface ConversationMapper extends BaseMapper<ConversationEntity> {
    @Update("""
            UPDATE nx_conversation c SET unread_count=(
                SELECT COUNT(*) FROM nx_conversation_message m
                LEFT JOIN nx_conversation_message_receipt r ON r.message_id=m.id
                WHERE m.conversation_no=c.conversation_no AND m.is_deleted=0 AND m.sender_type='user'
                  AND (r.message_id IS NULL OR r.receipt_status<>'read'))
            WHERE c.conversation_no=#{conversationNo} AND c.is_deleted=0
            """)
    int refreshUserUnreadCount(@Param("conversationNo") String conversationNo);
    @Select("SELECT COUNT(*) FROM nx_conversation WHERE is_deleted=0 AND status='OPEN'")
    long countOpen();

    @Select("SELECT COUNT(*) FROM nx_conversation WHERE is_deleted=0 AND status='TRANSFERRED' AND archived=0")
    long countIncomingPending();

    @Select("SELECT COUNT(*) FROM nx_conversation WHERE is_deleted=0 AND unread_count>0 AND status<>'CLOSED' AND archived=0")
    long countUnread();

    @Select("SELECT COUNT(*) FROM nx_conversation WHERE is_deleted=0 AND status='RESOLVED'")
    long countResolved();

    @Select("SELECT COUNT(*) FROM nx_conversation WHERE is_deleted=0 AND status='CLOSED'")
    long countClosed();

    @Select("SELECT COUNT(*) FROM nx_conversation WHERE is_deleted=0 AND archived=1")
    long countArchived();

    @Update("UPDATE nx_conversation SET archived=#{archived},version=version+1,updated_at=#{now} WHERE conversation_no=#{no} AND is_deleted=0 AND archived=#{previous} AND version=#{version}")
    int updateArchived(@Param("no") String no,@Param("archived") boolean archived,@Param("previous") boolean previous,@Param("version") Long version,@Param("now") LocalDateTime now);

    String SCOPED_COUNT_BASE = """
            <script>
            SELECT COUNT(*)
              FROM nx_conversation c
             WHERE c.is_deleted=0
             <if test='archived != null'>AND c.archived=#{archived}</if>
             <if test='status != null and status != ""'>AND c.status=UPPER(#{status})</if>
             <if test='type != null and type != ""'>AND c.conversation_type=LOWER(#{type})</if>
             <if test='ownerAgentId != null and ownerAgentId != ""'>AND EXISTS(SELECT 1 FROM nx_support_agent_user_assignment sa JOIN nx_support_agent_profile sp ON sp.admin_id=sa.agent_admin_id AND sp.enabled=1 AND sp.is_deleted=0 JOIN nx_admin a ON a.id=sa.agent_admin_id AND a.status=1 AND a.is_deleted=0 WHERE sa.user_id=c.user_id AND sa.agent_admin_id=#{ownerAgentId} AND sa.status='ACTIVE' AND sa.is_deleted=0)</if>
             <if test='userId != null'>AND c.user_id=#{userId}</if>
             <if test='keyword != null and keyword != ""'>
               AND (c.conversation_no LIKE CONCAT('%', #{keyword}, '%')
                    OR COALESCE((SELECT COALESCE(NULLIF(a.nickname,''),a.username) FROM nx_support_agent_user_assignment sa JOIN nx_admin a ON a.id=sa.agent_admin_id WHERE sa.user_id=c.user_id AND sa.status='ACTIVE' AND sa.is_deleted=0),'待分配') LIKE CONCAT('%', #{keyword}, '%')
                    OR c.last_message LIKE CONCAT('%', #{keyword}, '%'))
             </if>
             <if test='unreadOnly != null and unreadOnly'>AND c.unread_count &gt; 0 AND c.status &lt;&gt; 'CLOSED' AND c.archived=0</if>
            </script>
            """;
    @Select(SCOPED_COUNT_BASE)
    long countConversations(@Param("status") String status, @Param("type") String type,
                            @Param("ownerAgentId") String ownerAgentId, @Param("userId") Long userId,
                            @Param("keyword") String keyword, @Param("unreadOnly") Boolean unreadOnly,@Param("archived") Boolean archived);
    default long countConversations(String status,String type,String owner,Long user,String keyword,Boolean unread) {
        return countConversations(status,type,owner,user,keyword,unread,null);
    }

    String SCOPED_PAGE_BASE = """
            <script>
            SELECT
              c.id,
              c.conversation_no AS conversationNo,
              c.user_id AS userId,
              c.conversation_type AS conversationType,
              c.status,
              CAST((SELECT sa.agent_admin_id FROM nx_support_agent_user_assignment sa WHERE sa.user_id=c.user_id AND sa.status='ACTIVE' AND sa.is_deleted=0) AS CHAR) AS ownerAgentId,
              COALESCE((SELECT COALESCE(NULLIF(a.nickname,''),a.username) FROM nx_support_agent_user_assignment sa JOIN nx_admin a ON a.id=sa.agent_admin_id WHERE sa.user_id=c.user_id AND sa.status='ACTIVE' AND sa.is_deleted=0),'待分配') AS ownerAgentName,
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
              c.updated_at AS updatedAt,
              c.version,
              (SELECT COALESCE(MAX(m.id),0) FROM nx_conversation_message m
                WHERE m.conversation_no=c.conversation_no AND m.is_deleted=0
                  AND m.sender_type IN ('user','agent')) AS lastPublicMessageId,
              CASE WHEN c.status='CLOSED' AND EXISTS (
                SELECT 1 FROM nx_conversation_timeout_event e
                WHERE e.conversation_no=c.conversation_no AND e.event_type='CLOSE'
                  AND e.created_at=c.last_message_at
                  AND EXISTS (
                    SELECT 1 FROM nx_conversation_message sm
                    WHERE sm.conversation_no=c.conversation_no AND sm.is_deleted=0
                      AND sm.sender_type='system' AND sm.created_at=e.created_at
                      AND sm.content=c.last_message
                      AND NOT EXISTS (SELECT 1 FROM nx_conversation_message m
                        WHERE m.conversation_no=c.conversation_no AND m.sender_type IN ('user','agent')
                          AND m.id>sm.id)
                  )
              ) THEN 'IDLE_TIMEOUT_CLOSE' ELSE NULL END AS lastMessageKind, c.archived
            FROM nx_conversation c
            LEFT JOIN nx_conversation_transfer t
              ON t.conversation_no=c.conversation_no AND t.status='PENDING' AND t.is_deleted=0
            WHERE c.is_deleted=0
             <if test='archived != null'>AND c.archived=#{archived}</if>
             <if test='status != null and status != ""'>AND c.status=UPPER(#{status})</if>
             <if test='type != null and type != ""'>AND c.conversation_type=LOWER(#{type})</if>
             <if test='ownerAgentId != null and ownerAgentId != ""'>AND EXISTS(SELECT 1 FROM nx_support_agent_user_assignment sa JOIN nx_support_agent_profile sp ON sp.admin_id=sa.agent_admin_id AND sp.enabled=1 AND sp.is_deleted=0 JOIN nx_admin a ON a.id=sa.agent_admin_id AND a.status=1 AND a.is_deleted=0 WHERE sa.user_id=c.user_id AND sa.agent_admin_id=#{ownerAgentId} AND sa.status='ACTIVE' AND sa.is_deleted=0)</if>
             <if test='userId != null'>AND c.user_id=#{userId}</if>
             <if test='keyword != null and keyword != ""'>
               AND (c.conversation_no LIKE CONCAT('%', #{keyword}, '%')
                    OR COALESCE((SELECT COALESCE(NULLIF(a.nickname,''),a.username) FROM nx_support_agent_user_assignment sa JOIN nx_admin a ON a.id=sa.agent_admin_id WHERE sa.user_id=c.user_id AND sa.status='ACTIVE' AND sa.is_deleted=0),'待分配') LIKE CONCAT('%', #{keyword}, '%')
                    OR c.last_message LIKE CONCAT('%', #{keyword}, '%'))
             </if>
             <if test='unreadOnly != null and unreadOnly'>AND c.unread_count &gt; 0 AND c.status &lt;&gt; 'CLOSED' AND c.archived=0</if>
             <if test='stableCursor != null and stableCursor and beforeId != null'>AND c.id &lt; #{beforeId}</if>
             <choose>
               <when test='stableCursor != null and stableCursor'>ORDER BY c.id DESC</when>
               <otherwise>ORDER BY COALESCE(c.last_message_at,c.updated_at,c.created_at) DESC, c.id DESC</otherwise>
             </choose>
            LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """;
    @Select(SCOPED_PAGE_BASE)
    List<ContentConversationView> pageConversations(@Param("status") String status, @Param("type") String type,
                                                     @Param("ownerAgentId") String ownerAgentId, @Param("keyword") String keyword,
                                                     @Param("userId") Long userId, @Param("unreadOnly") Boolean unreadOnly,
                                                     @Param("beforeId") Long beforeId, @Param("stableCursor") Boolean stableCursor,
                                                     @Param("pageSize") long pageSize,
                                                    @Param("offset") long offset,@Param("archived") Boolean archived);
    default List<ContentConversationView> pageConversations(String status,String type,String owner,String keyword,Long user,Boolean unread,Long before,Boolean stable,long size,long offset) {
        return pageConversations(status,type,owner,keyword,user,unread,before,stable,size,offset,null);
    }

    @Select("""
            <script>
            SELECT
              c.id,
              c.conversation_no AS conversationNo,
              c.user_id AS userId,
              c.conversation_type AS conversationType,
              c.status,
              CAST((SELECT sa.agent_admin_id FROM nx_support_agent_user_assignment sa WHERE sa.user_id=c.user_id AND sa.status='ACTIVE' AND sa.is_deleted=0 <if test='currentRead'>FOR SHARE</if>) AS CHAR) AS ownerAgentId,
              COALESCE((SELECT COALESCE(NULLIF(a.nickname,''),a.username) FROM nx_support_agent_user_assignment sa JOIN nx_admin a ON a.id=sa.agent_admin_id WHERE sa.user_id=c.user_id AND sa.status='ACTIVE' AND sa.is_deleted=0 <if test='currentRead'>FOR SHARE</if>),'待分配') AS ownerAgentName,
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
              c.updated_at AS updatedAt,
              c.version,
              (SELECT COALESCE(MAX(m.id),0) FROM nx_conversation_message m
                WHERE m.conversation_no=c.conversation_no AND m.is_deleted=0
                  AND m.sender_type IN ('user','agent') <if test='currentRead'>FOR SHARE</if>) AS lastPublicMessageId,
              CASE WHEN c.status='CLOSED' AND EXISTS (
                SELECT 1 FROM nx_conversation_timeout_event e
                WHERE e.conversation_no=c.conversation_no AND e.event_type='CLOSE'
                  AND e.created_at=c.last_message_at
                  AND EXISTS (
                    SELECT 1 FROM nx_conversation_message sm
                    WHERE sm.conversation_no=c.conversation_no AND sm.is_deleted=0
                      AND sm.sender_type='system' AND sm.created_at=e.created_at
                      AND sm.content=c.last_message
                      AND NOT EXISTS (SELECT 1 FROM nx_conversation_message m
                        WHERE m.conversation_no=c.conversation_no AND m.sender_type IN ('user','agent')
                          AND m.id>sm.id <if test="currentRead">FOR SHARE</if>)
                    <if test="currentRead">FOR SHARE</if>
                  )
                <if test="currentRead">FOR SHARE</if>
              ) THEN 'IDLE_TIMEOUT_CLOSE' ELSE NULL END AS lastMessageKind, c.archived
            FROM nx_conversation c
            LEFT JOIN nx_conversation_transfer t
              ON t.conversation_no=c.conversation_no AND t.status='PENDING' AND t.is_deleted=0
            WHERE c.is_deleted=0 AND c.conversation_no=#{conversationNo}
            LIMIT 1
            <if test="currentRead">FOR SHARE</if>
            </script>
            """)
    ContentConversationView selectConversation(@Param("conversationNo") String conversationNo, @Param("currentRead") boolean currentRead);

    default ContentConversationView findByConversationNo(String no) { return selectConversation(no,false); }
    default ContentConversationView findCurrentByConversationNo(String no) { return selectConversation(no,true); }

    @Select("""
            SELECT id
              FROM nx_conversation
             WHERE conversation_no=#{conversationNo} AND is_deleted=0
             FOR UPDATE
            """)
    Long lockConversationHeader(@Param("conversationNo") String conversationNo);

    @Select("""
            SELECT id
              FROM nx_conversation_transfer
             WHERE conversation_no=#{conversationNo} AND status='PENDING' AND is_deleted=0
             ORDER BY id ASC
             FOR UPDATE
            """)
    List<Long> lockPendingTransfers(@Param("conversationNo") String conversationNo);

    @Select("""
            SELECT
              c.id,
              c.conversation_no AS conversationNo,
              c.user_id AS userId,
              c.conversation_type AS conversationType,
              c.status,
              CAST((SELECT sa.agent_admin_id FROM nx_support_agent_user_assignment sa WHERE sa.user_id=c.user_id AND sa.status='ACTIVE' AND sa.is_deleted=0) AS CHAR) AS ownerAgentId,
              COALESCE((SELECT COALESCE(NULLIF(a.nickname,''),a.username) FROM nx_support_agent_user_assignment sa JOIN nx_admin a ON a.id=sa.agent_admin_id WHERE sa.user_id=c.user_id AND sa.status='ACTIVE' AND sa.is_deleted=0),'待分配') AS ownerAgentName,
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
              c.updated_at AS updatedAt,
              c.version,
              (SELECT COALESCE(MAX(m.id),0) FROM nx_conversation_message m
                WHERE m.conversation_no=c.conversation_no AND m.is_deleted=0
                  AND m.sender_type IN ('user','agent')) AS lastPublicMessageId,
              NULL AS lastMessageKind, c.archived
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
               SET status='TRANSFERRED', owner_agent_id=#{targetId}, owner_agent_name=#{targetName}, version=version+1, updated_at=#{now}
             WHERE conversation_no=#{conversationNo} AND status='OPEN' AND version=#{expectedVersion} AND is_deleted=0
            """)
    int markTransferred(@Param("conversationNo") String conversationNo, @Param("targetId") String targetId,
                        @Param("targetName") String targetName, @Param("expectedVersion") Long expectedVersion,
                        @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_conversation
               SET status='TRANSFERRED',
                   owner_agent_id=#{targetId},
                   owner_agent_name=#{targetName},
                   last_message=CONCAT('Transferred to ', #{targetName}),
                   last_message_at=#{now},
                   version=version+1,
                   updated_at=#{now}
             WHERE conversation_no=#{conversationNo} AND is_deleted=0
               AND status='TRANSFERRED'
               AND version=#{expectedVersion}
            """)
    int fallbackConversation(@Param("conversationNo") String conversationNo, @Param("targetId") String targetId,
                             @Param("targetName") String targetName, @Param("expectedVersion") Long expectedVersion,
                             @Param("now") LocalDateTime now);

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
               SET status='OPEN', owner_agent_id=#{ownerAgentId}, owner_agent_name=#{ownerAgentName}, version=version+1, updated_at=#{now}
             WHERE conversation_no=#{conversationNo} AND status='TRANSFERRED' AND version=#{expectedVersion} AND is_deleted=0
            """)
    int acceptConversation(@Param("conversationNo") String conversationNo,
                           @Param("ownerAgentId") String ownerAgentId,
                           @Param("ownerAgentName") String ownerAgentName,
                           @Param("expectedVersion") Long expectedVersion,
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
               SET status='OPEN', owner_agent_id=#{fromAgentId}, owner_agent_name=#{fromAgentName}, version=version+1, updated_at=#{now}
             WHERE conversation_no=#{conversationNo} AND status='TRANSFERRED' AND version=#{expectedVersion} AND is_deleted=0
            """)
    int returnConversation(@Param("conversationNo") String conversationNo, @Param("fromAgentId") String fromAgentId,
                           @Param("fromAgentName") String fromAgentName, @Param("expectedVersion") Long expectedVersion,
                           @Param("now") LocalDateTime now);

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
                   version=version+1,
                   updated_at=#{now}
             WHERE conversation_no=#{conversationNo} AND is_deleted=0
               AND status='TRANSFERRED'
               AND version=#{expectedVersion}
               AND EXISTS (
                   SELECT 1 FROM nx_conversation_transfer t
                    WHERE t.conversation_no=#{conversationNo} AND t.status='PENDING' AND t.is_deleted=0
               )
            """)
    int markTransferWait(@Param("conversationNo") String conversationNo, @Param("message") String message,
                         @Param("expectedVersion") Long expectedVersion,
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
                   version=version+1,
                   updated_at=#{now}
             WHERE conversation_no=#{conversationNo} AND is_deleted=0
               AND status=#{expectedStatus}
               AND version=#{expectedVersion}
            """)
    int replyConversation(@Param("conversationNo") String conversationNo, @Param("body") String body,
                          @Param("expectedStatus") String expectedStatus,
                          @Param("expectedVersion") Long expectedVersion,
                          @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_conversation
               SET status=CASE WHEN status='RESOLVED' THEN 'OPEN' ELSE status END,
                   unread_count=unread_count+1,
                   last_message=#{body},
                   last_message_at=#{now},
                   version=version+1,
                   updated_at=#{now}
             WHERE conversation_no=#{conversationNo} AND user_id=#{userId} AND is_deleted=0
               AND status=#{expectedStatus} AND status IN ('OPEN','RESOLVED') AND version=#{expectedVersion}
            """)
    int replyConversationAsUser(
            @Param("conversationNo") String conversationNo,
            @Param("userId") Long userId,
            @Param("body") String body,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersion") Long expectedVersion,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_conversation
               SET status=#{status}, version=version+1, updated_at=#{now}
             WHERE conversation_no=#{conversationNo} AND is_deleted=0
               AND status=#{expectedStatus}
               AND version=#{expectedVersion}
            """)
    int updateConversationStatus(@Param("conversationNo") String conversationNo, @Param("status") String status,
                                 @Param("expectedStatus") String expectedStatus,
                                 @Param("expectedVersion") Long expectedVersion,
                                 @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_conversation
               SET status='CLOSED',
                   last_message=#{message},
                   last_message_at=#{now},
                   version=version+1,
                   updated_at=#{now}
             WHERE conversation_no=#{conversationNo}
               AND status<>'CLOSED'
               AND version=#{expectedVersion}
               AND is_deleted=0
            """)
    int markConvertedToTicket(@Param("conversationNo") String conversationNo, @Param("message") String message,
                              @Param("expectedVersion") Long expectedVersion,
                              @Param("now") LocalDateTime now);

    @SelectProvider(type=ScopedSql.class,method="count")
    long countConversationsScoped(@Param("status") String status,@Param("type") String type,
            @Param("ownerAgentId") String owner,@Param("userId") Long user,@Param("keyword") String keyword,
            @Param("unreadOnly") Boolean unread,@Param("archived") Boolean archived,@Param("scope") ReadScope scope);
    @SelectProvider(type=ScopedSql.class,method="page")
    List<ContentConversationView> pageConversationsScoped(@Param("status") String status,@Param("type") String type,
            @Param("ownerAgentId") String owner,@Param("keyword") String keyword,@Param("userId") Long user,
            @Param("unreadOnly") Boolean unread,@Param("beforeId") Long before,@Param("stableCursor") Boolean stable,
            @Param("pageSize") long size,@Param("offset") long offset,@Param("archived") Boolean archived,@Param("scope") ReadScope scope);
    final class ScopedSql {
        private ScopedSql() {}
        public static String count(){return scoped(SCOPED_COUNT_BASE);}
        public static String page(){return scoped(SCOPED_PAGE_BASE);}
        private static String scoped(String sql) {
            return sql.replace("WHERE c.is_deleted=0",
                "JOIN nx_user scope_customer ON scope_customer.id=c.user_id WHERE c.is_deleted=0 "
                    +SupportBindingMapper.CUSTOMER_SCOPE_PREDICATE);
        }
    }
}
