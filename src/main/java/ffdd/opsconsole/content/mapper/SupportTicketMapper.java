package ffdd.opsconsole.content.mapper;

import ffdd.opsconsole.content.domain.SupportTicketView;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import org.apache.ibatis.annotations.Update;

/** Historical ticket reads only (global search, advisor-binding owner projection); ticket workflows are retired. */
// No entity CRUD: ticket rows are history and must not be created through a generic mapper again.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportTicketMapper {
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
             ORDER BY COALESCE(t.last_message_at,t.updated_at,t.created_at) DESC, t.id DESC
            LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """;
    @SelectProvider(type=ScopedSql.class,method="count")
    long countTicketsScoped(@Param("ticketScope") String ticketScope,@Param("status") String status,@Param("category") String category,
            @Param("priority") String priority,@Param("assignedAdminId") Long assigned,@Param("userId") Long user,
            @Param("keyword") String keyword,@Param("visibility") Visibility visibility,@Param("scope") ReadScope scope);
    @SelectProvider(type=ScopedSql.class,method="page")
    List<SupportTicketView> pageTicketsScoped(@Param("ticketScope") String ticketScope,@Param("status") String status,@Param("category") String category,
            @Param("priority") String priority,@Param("assignedAdminId") Long assigned,@Param("userId") Long user,
            @Param("keyword") String keyword,
            @Param("pageSize") long size,@Param("offset") long offset,@Param("visibility") Visibility visibility,@Param("scope") ReadScope scope);
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
