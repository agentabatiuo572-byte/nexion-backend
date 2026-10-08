package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.domain.SupportTicketView;
import ffdd.opsconsole.content.infrastructure.SupportTicketEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import org.apache.ibatis.annotations.Update;

public interface SupportTicketMapper extends BaseMapper<SupportTicketEntity> {
    @Select("""
            SELECT x.agent_admin_id AS adminId,
                   COALESCE(NULLIF(TRIM(a.nickname),''),NULLIF(TRIM(a.username),''),CAST(x.agent_admin_id AS CHAR)) AS name
              FROM nx_support_agent_user_assignment x LEFT JOIN nx_admin a ON a.id=x.agent_admin_id
             WHERE x.user_id=#{customerId} AND x.status='ACTIVE' AND x.is_deleted=0
             FOR SHARE
            """)
    ffdd.opsconsole.content.domain.DedicatedAdvisorBindingView currentOwner(Long customerId);

    @Update("""
            UPDATE nx_support_ticket
               SET assigned_admin_id=#{adminId},assigned_admin_name=#{name},version=version+1,updated_at=#{now}
             WHERE user_id=#{customerId} AND is_deleted=0
               AND NOT (assigned_admin_id <=> #{adminId} AND assigned_admin_name <=> #{name})
            """)
    int synchronizeOwner(@Param("customerId") Long customerId, @Param("adminId") Long adminId,
                         @Param("name") String name, @Param("now") LocalDateTime now);

    record Visibility(Long adminId, Long customerId, boolean privateRead, boolean supervisor) {}
    String VISIBLE = " COALESCE((t.source_conversation_no='DIRECT' OR t.user_id=#{visibility.customerId} OR (#{visibility.privateRead} AND (#{visibility.supervisor} OR EXISTS(SELECT 1 FROM nx_support_agent_user_assignment a WHERE a.user_id=t.user_id AND a.agent_admin_id=#{visibility.adminId} AND a.status='ACTIVE' AND a.is_deleted=0)))),0) ";

    @Update("UPDATE nx_support_ticket SET source_conversation_no=#{source} WHERE ticket_no=#{ticketNo} AND source_conversation_no='DIRECT' AND is_deleted=0")
    int markConversationSource(@Param("ticketNo") String ticketNo,@Param("source") String source);
    @Update("""
            UPDATE nx_support_ticket SET user_unread_count=0, updated_at=#{now}, version=version+1
            WHERE ticket_no=#{ticketNo} AND user_id=#{userId} AND is_deleted=0
              AND status=#{expectedStatus} AND version=#{expectedVersion}
            """)
    int markUserRead(@Param("ticketNo") String ticketNo, @Param("userId") Long userId,
                     @Param("expectedStatus") String expectedStatus, @Param("expectedVersion") Long expectedVersion,
                     @Param("now") LocalDateTime now);

    @Select("SELECT COUNT(*) FROM nx_support_ticket WHERE is_deleted=0 AND archived=0 AND status IN ('OPEN','IN_PROGRESS','PENDING_USER')")
    long countActive();

    @Select("SELECT COUNT(*) FROM nx_support_ticket WHERE is_deleted=0 AND archived=0 AND status='PENDING_USER'")
    long countPendingUser();

    @Select("SELECT COUNT(*) FROM nx_support_ticket WHERE is_deleted=0 AND archived=0 AND ops_unread_count>0 AND status<>'CLOSED'")
    long countOpsUnread();

    @Select("SELECT COUNT(*) FROM nx_support_ticket WHERE is_deleted=0 AND archived=0 AND priority IN ('HIGH','URGENT') AND status IN ('OPEN','IN_PROGRESS','PENDING_USER')")
    long countHighPriorityActive();

    @Select("SELECT COUNT(*) FROM nx_support_ticket WHERE is_deleted=0 AND archived=1")
    long countArchived();

    String SCOPED_COUNT_BASE = """
            <script>
            SELECT COUNT(*)
             FROM nx_support_ticket t
             WHERE t.is_deleted=0
             <if test='scope == "active"'>AND t.archived=0</if>
             <if test='scope == "resolved"'>AND t.archived=0</if>
             <if test='scope == "archived"'>AND t.archived=1</if>
             <if test='status != null and status != ""'>AND t.status=UPPER(#{status})</if>
             <if test='(status == null or status == "") and scope == "active"'>AND t.status IN ('OPEN','IN_PROGRESS','PENDING_USER')</if>
             <if test='(status == null or status == "") and scope == "resolved"'>AND t.status IN ('RESOLVED','CLOSED')</if>
             <if test='category != null and category != ""'>AND t.category=LOWER(#{category})</if>
             <if test='priority != null and priority != ""'>AND t.priority=UPPER(#{priority})</if>
             <if test='assignedAdminId != null'>AND t.assigned_admin_id=#{assignedAdminId}</if>
             <if test='userId != null'>AND t.user_id=#{userId}</if>
             <if test='keyword != null and keyword != ""'>
               AND (t.ticket_no LIKE CONCAT('%', #{keyword}, '%')
                    OR (""" + VISIBLE + """
 AND t.title LIKE CONCAT('%', #{keyword}, '%'))
                    OR t.assigned_admin_name LIKE CONCAT('%', #{keyword}, '%')
                    OR (""" + VISIBLE + """
 AND t.last_message LIKE CONCAT('%', #{keyword}, '%')))
             </if>
            </script>
            """;
    @Select(SCOPED_COUNT_BASE)
    long countTickets(@Param("scope") String scope, @Param("status") String status, @Param("category") String category,
                      @Param("priority") String priority, @Param("assignedAdminId") Long assignedAdminId,
                      @Param("userId") Long userId, @Param("keyword") String keyword,@Param("visibility") Visibility visibility);

    String SCOPED_PAGE_BASE = """
            <script>
            SELECT
              t.id,
              t.ticket_no AS ticketNo,
              t.user_id AS userId,
              t.category,
              t.priority,
              t.status,
              CASE WHEN """ + VISIBLE + """
 THEN t.title ELSE '私聊内容仅当前顾问和主管可阅' END AS title,
              CASE WHEN """ + VISIBLE + """
 THEN t.last_message ELSE '私聊内容仅当前顾问和主管可阅' END AS lastMessage,
              t.assigned_admin_id AS assignedAdminId,
              t.assigned_admin_name AS assignedAdminName,
              t.user_unread_count AS userUnreadCount,
              t.ops_unread_count AS opsUnreadCount,
              t.message_count AS messageCount,
              t.last_message_at AS lastMessageAt,
              t.closed_at AS closedAt,
              t.created_at AS createdAt,
              t.updated_at AS updatedAt,
              t.archived,
              t.archived_at AS archivedAt,
              t.version,
              EXISTS(SELECT 1 FROM nx_user u WHERE u.id=t.user_id AND u.is_deleted=0) AS userExists,
              t.source_conversation_no AS sourceConversationNo,
              NOT """ + VISIBLE + """
 AS contentRestricted
            FROM nx_support_ticket t
            WHERE t.is_deleted=0
             <if test='scope == "active"'>AND t.archived=0</if>
             <if test='scope == "resolved"'>AND t.archived=0</if>
             <if test='scope == "archived"'>AND t.archived=1</if>
             <if test='status != null and status != ""'>AND t.status=UPPER(#{status})</if>
             <if test='(status == null or status == "") and scope == "active"'>AND t.status IN ('OPEN','IN_PROGRESS','PENDING_USER')</if>
             <if test='(status == null or status == "") and scope == "resolved"'>AND t.status IN ('RESOLVED','CLOSED')</if>
             <if test='category != null and category != ""'>AND t.category=LOWER(#{category})</if>
             <if test='priority != null and priority != ""'>AND t.priority=UPPER(#{priority})</if>
             <if test='assignedAdminId != null'>AND t.assigned_admin_id=#{assignedAdminId}</if>
             <if test='userId != null'>AND t.user_id=#{userId}</if>
             <if test='keyword != null and keyword != ""'>
               AND (t.ticket_no LIKE CONCAT('%', #{keyword}, '%')
                    OR (""" + VISIBLE + """
 AND t.title LIKE CONCAT('%', #{keyword}, '%'))
                    OR t.assigned_admin_name LIKE CONCAT('%', #{keyword}, '%')
                    OR (""" + VISIBLE + """
 AND t.last_message LIKE CONCAT('%', #{keyword}, '%')))
             </if>
             <if test='stableCursor != null and stableCursor and beforeId != null'>AND t.id &lt; #{beforeId}</if>
             <choose>
               <when test='stableCursor != null and stableCursor'>ORDER BY t.id DESC</when>
               <otherwise>ORDER BY COALESCE(t.last_message_at,t.updated_at,t.created_at) DESC, t.id DESC</otherwise>
             </choose>
            LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """;
    @Select(SCOPED_PAGE_BASE)
    List<SupportTicketView> pageTickets(@Param("scope") String scope, @Param("status") String status, @Param("category") String category,
                                        @Param("priority") String priority, @Param("assignedAdminId") Long assignedAdminId,
                                        @Param("userId") Long userId, @Param("keyword") String keyword,
                                        @Param("beforeId") Long beforeId, @Param("stableCursor") Boolean stableCursor,
                                        @Param("pageSize") long pageSize, @Param("offset") long offset,@Param("visibility") Visibility visibility);

    @Select("""
            SELECT
              t.id,
              t.ticket_no AS ticketNo,
              t.user_id AS userId,
              t.category,
              t.priority,
              t.status,
              CASE WHEN """ + VISIBLE + """
 THEN t.title ELSE '私聊内容仅当前顾问和主管可阅' END AS title,
              CASE WHEN """ + VISIBLE + """
 THEN t.last_message ELSE '私聊内容仅当前顾问和主管可阅' END AS lastMessage,
              t.assigned_admin_id AS assignedAdminId,
              t.assigned_admin_name AS assignedAdminName,
              t.user_unread_count AS userUnreadCount,
              t.ops_unread_count AS opsUnreadCount,
              t.message_count AS messageCount,
              t.last_message_at AS lastMessageAt,
              t.closed_at AS closedAt,
              t.created_at AS createdAt,
              t.updated_at AS updatedAt,
              t.archived,
              t.archived_at AS archivedAt,
              t.version,
              EXISTS(SELECT 1 FROM nx_user u WHERE u.id=t.user_id AND u.is_deleted=0) AS userExists,
              t.source_conversation_no AS sourceConversationNo,
              NOT """ + VISIBLE + """
 AS contentRestricted
            FROM nx_support_ticket t
            WHERE t.is_deleted=0 AND t.ticket_no=#{ticketNo}
            LIMIT 1
            """)
    SupportTicketView findByTicketNo(@Param("ticketNo") String ticketNo,@Param("visibility") Visibility visibility);

    @Update("""
            UPDATE nx_support_ticket
               SET status=#{status},
                   closed_at=CASE WHEN #{status} IN ('RESOLVED','CLOSED') THEN #{now} ELSE NULL END,
                   updated_at=#{now},
                   version=version+1
             WHERE ticket_no=#{ticketNo} AND is_deleted=0
               AND status=#{expectedStatus} AND version=#{expectedVersion} AND archived=0
            """)
    int updateStatus(
            @Param("ticketNo") String ticketNo,
            @Param("status") String status,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_support_ticket
               SET priority=#{priority}, updated_at=#{now}, version=version+1
             WHERE ticket_no=#{ticketNo} AND is_deleted=0
               AND status=#{expectedStatus} AND version=#{expectedVersion} AND archived=0
            """)
    int updatePriority(
            @Param("ticketNo") String ticketNo,
            @Param("priority") String priority,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_support_ticket
               SET assigned_admin_id=#{assignedAdminId}, assigned_admin_name=#{assignedAdminName},
                   updated_at=#{now}, version=version+1
             WHERE ticket_no=#{ticketNo} AND is_deleted=0
               AND status=#{expectedStatus} AND version=#{expectedVersion} AND archived=0
            """)
    int assign(@Param("ticketNo") String ticketNo, @Param("assignedAdminId") Long assignedAdminId,
               @Param("assignedAdminName") String assignedAdminName,
               @Param("expectedStatus") String expectedStatus,
               @Param("expectedVersion") long expectedVersion,
               @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_support_ticket
               SET archived=#{archived}, archived_at=CASE WHEN #{archived}=1 THEN #{now} ELSE NULL END,
                   updated_at=#{now}, version=version+1
             WHERE ticket_no=#{ticketNo} AND is_deleted=0
               AND status=#{expectedStatus} AND version=#{expectedVersion}
            """)
    int archive(
            @Param("ticketNo") String ticketNo,
            @Param("archived") boolean archived,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_support_ticket
               SET status='PENDING_USER',
                   last_message=#{body},
                   last_message_at=#{now},
                   ops_unread_count=0,
                   user_unread_count=user_unread_count+1,
                   message_count=message_count+1,
                   closed_at=NULL,
                   updated_at=#{now},
                   version=version+1
             WHERE ticket_no=#{ticketNo} AND is_deleted=0
               AND status=#{expectedStatus} AND version=#{expectedVersion} AND archived=0
            """)
    int appendReplyHeader(
            @Param("ticketNo") String ticketNo,
            @Param("body") String body,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_support_ticket
               SET status=CASE WHEN status IN ('IN_PROGRESS','PENDING_USER','RESOLVED') THEN 'OPEN' ELSE status END,
                   last_message=#{body},
                   last_message_at=#{now},
                   user_unread_count=0,
                   ops_unread_count=ops_unread_count+1,
                   message_count=message_count+1,
                   closed_at=NULL,
                   updated_at=#{now},
                   version=version+1
             WHERE ticket_no=#{ticketNo} AND user_id=#{userId} AND is_deleted=0
               AND archived=0 AND status=#{expectedStatus} AND status<>'CLOSED' AND version=#{expectedVersion}
            """)
    int appendUserReplyHeader(
            @Param("ticketNo") String ticketNo,
            @Param("userId") Long userId,
            @Param("body") String body,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_support_ticket
               SET message_count=message_count+1,
                   updated_at=#{now},
                   version=version+1
             WHERE ticket_no=#{ticketNo} AND is_deleted=0
               AND status=#{expectedStatus} AND version=#{expectedVersion} AND archived=0
            """)
    int appendInternalNoteHeader(
            @Param("ticketNo") String ticketNo,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_support_ticket
               SET last_message=#{body},
                   last_message_at=#{now},
                   message_count=message_count+1,
                   updated_at=#{now}
             WHERE ticket_no=#{ticketNo} AND is_deleted=0
            """)
    int appendSystemTraceHeader(@Param("ticketNo") String ticketNo, @Param("body") String body, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_support_ticket
               SET last_message=#{body},
                   last_message_at=#{now},
                   message_count=message_count+1,
                   updated_at=#{now},
                   version=version+1
             WHERE ticket_no=#{ticketNo} AND is_deleted=0
               AND status=#{expectedStatus} AND version=#{expectedVersion} AND archived=0
            """)
    int appendSystemTraceHeaderCas(
            @Param("ticketNo") String ticketNo,
            @Param("body") String body,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    @SelectProvider(type=ScopedSql.class,method="count")
    long countTicketsScoped(@Param("ticketScope") String ticketScope,@Param("status") String status,@Param("category") String category,
            @Param("priority") String priority,@Param("assignedAdminId") Long assigned,@Param("userId") Long user,
            @Param("keyword") String keyword,@Param("visibility") Visibility visibility,@Param("scope") ReadScope scope);
    @SelectProvider(type=ScopedSql.class,method="page")
    List<SupportTicketView> pageTicketsScoped(@Param("ticketScope") String ticketScope,@Param("status") String status,@Param("category") String category,
            @Param("priority") String priority,@Param("assignedAdminId") Long assigned,@Param("userId") Long user,
            @Param("keyword") String keyword,@Param("beforeId") Long before,@Param("stableCursor") Boolean stable,
            @Param("pageSize") long size,@Param("offset") long offset,@Param("visibility") Visibility visibility,@Param("scope") ReadScope scope);
    @Select("<script>SELECT COALESCE(SUM(t.archived=0 AND t.status IN ('OPEN','IN_PROGRESS','PENDING_USER')),0) active,"
        +"COALESCE(SUM(t.archived=0 AND t.status='PENDING_USER'),0) pendingUser,"
        +"COALESCE(SUM(t.archived=0 AND t.ops_unread_count &gt; 0 AND t.status &lt;&gt; 'CLOSED'),0) opsUnread,"
        +"COALESCE(SUM(t.archived=0 AND t.priority IN ('HIGH','URGENT') AND t.status IN ('OPEN','IN_PROGRESS','PENDING_USER')),0) highPriorityActive,"
        +"COALESCE(SUM(t.archived=1),0) archived FROM nx_support_ticket t JOIN nx_user scope_customer ON scope_customer.id=t.user_id "
        +"WHERE t.is_deleted=0 "+SupportBindingMapper.CUSTOMER_SCOPE_PREDICATE+"</script>")
    java.util.Map<String,Object> scopedCounters(@Param("scope") ReadScope scope);
    final class ScopedSql {
        private ScopedSql() {}
        public static String count(){return scoped(SCOPED_COUNT_BASE);}
        public static String page(){return scoped(SCOPED_PAGE_BASE);}
        private static String scoped(String sql) {
            return sql.replace("scope ==", "ticketScope ==")
                .replace("WHERE t.is_deleted=0",
                    "JOIN nx_user scope_customer ON scope_customer.id=t.user_id WHERE t.is_deleted=0 "
                        +SupportBindingMapper.CUSTOMER_SCOPE_PREDICATE);
        }
    }
}
