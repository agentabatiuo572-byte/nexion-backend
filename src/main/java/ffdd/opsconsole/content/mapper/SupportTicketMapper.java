package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.domain.SupportTicketView;
import ffdd.opsconsole.content.infrastructure.SupportTicketEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface SupportTicketMapper extends BaseMapper<SupportTicketEntity> {
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

    @Select("""
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
                    OR t.title LIKE CONCAT('%', #{keyword}, '%')
                    OR t.assigned_admin_name LIKE CONCAT('%', #{keyword}, '%')
                    OR t.last_message LIKE CONCAT('%', #{keyword}, '%'))
             </if>
            </script>
            """)
    long countTickets(@Param("scope") String scope, @Param("status") String status, @Param("category") String category,
                      @Param("priority") String priority, @Param("assignedAdminId") Long assignedAdminId,
                      @Param("userId") Long userId, @Param("keyword") String keyword);

    @Select("""
            <script>
            SELECT
              t.id,
              t.ticket_no AS ticketNo,
              t.user_id AS userId,
              t.category,
              t.priority,
              t.status,
              t.title,
              t.last_message AS lastMessage,
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
              t.archived_at AS archivedAt
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
                    OR t.title LIKE CONCAT('%', #{keyword}, '%')
                    OR t.assigned_admin_name LIKE CONCAT('%', #{keyword}, '%')
                    OR t.last_message LIKE CONCAT('%', #{keyword}, '%'))
             </if>
            ORDER BY COALESCE(t.last_message_at,t.updated_at,t.created_at) DESC
            LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    List<SupportTicketView> pageTickets(@Param("scope") String scope, @Param("status") String status, @Param("category") String category,
                                        @Param("priority") String priority, @Param("assignedAdminId") Long assignedAdminId,
                                        @Param("userId") Long userId, @Param("keyword") String keyword,
                                        @Param("pageSize") long pageSize, @Param("offset") long offset);

    @Select("""
            SELECT
              t.id,
              t.ticket_no AS ticketNo,
              t.user_id AS userId,
              t.category,
              t.priority,
              t.status,
              t.title,
              t.last_message AS lastMessage,
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
              t.archived_at AS archivedAt
            FROM nx_support_ticket t
            WHERE t.is_deleted=0 AND t.ticket_no=#{ticketNo}
            LIMIT 1
            """)
    SupportTicketView findByTicketNo(@Param("ticketNo") String ticketNo);

    @Update("""
            UPDATE nx_support_ticket
               SET status=#{status},
                   closed_at=CASE WHEN #{status} IN ('RESOLVED','CLOSED') THEN #{now} ELSE NULL END,
                   updated_at=#{now}
             WHERE ticket_no=#{ticketNo} AND is_deleted=0
            """)
    int updateStatus(@Param("ticketNo") String ticketNo, @Param("status") String status, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_support_ticket
               SET priority=#{priority}, updated_at=#{now}
             WHERE ticket_no=#{ticketNo} AND is_deleted=0
            """)
    int updatePriority(@Param("ticketNo") String ticketNo, @Param("priority") String priority, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_support_ticket
               SET assigned_admin_id=#{assignedAdminId}, assigned_admin_name=#{assignedAdminName}, updated_at=#{now}
             WHERE ticket_no=#{ticketNo} AND is_deleted=0
            """)
    int assign(@Param("ticketNo") String ticketNo, @Param("assignedAdminId") Long assignedAdminId,
               @Param("assignedAdminName") String assignedAdminName, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_support_ticket
               SET archived=#{archived}, archived_at=CASE WHEN #{archived}=1 THEN #{now} ELSE NULL END, updated_at=#{now}
             WHERE ticket_no=#{ticketNo} AND is_deleted=0
            """)
    int archive(@Param("ticketNo") String ticketNo, @Param("archived") boolean archived, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_support_ticket
               SET status='PENDING_USER',
                   last_message=#{body},
                   last_message_at=#{now},
                   ops_unread_count=0,
                   user_unread_count=user_unread_count+1,
                   message_count=message_count+1,
                   closed_at=NULL,
                   updated_at=#{now}
             WHERE ticket_no=#{ticketNo} AND is_deleted=0
            """)
    int appendReplyHeader(@Param("ticketNo") String ticketNo, @Param("body") String body, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_support_ticket
               SET last_message=#{body},
                   last_message_at=#{now},
                   message_count=message_count+1,
                   updated_at=#{now}
             WHERE ticket_no=#{ticketNo} AND is_deleted=0
            """)
    int appendSystemTraceHeader(@Param("ticketNo") String ticketNo, @Param("body") String body, @Param("now") LocalDateTime now);
}
