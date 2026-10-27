package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.domain.SupportTicketMessageView;
import ffdd.opsconsole.content.infrastructure.SupportTicketMessageEntity;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** M-37: rows written with an advisor login name display that advisor's name. */
public interface SupportTicketMessageMapper extends BaseMapper<SupportTicketMessageEntity> {
    @Select("""
            SELECT
              id,
              ticket_id AS ticketId,
              ticket_no AS ticketNo,
              sender_id AS senderId,
              sender_type AS senderType,
              CASE WHEN sender_type<>'user' AND EXISTS(SELECT 1 FROM nx_admin login_admin WHERE login_admin.id=sender_id AND login_admin.username=sender_name) THEN (SELECT COALESCE(NULLIF(TRIM(name_admin.nickname),''),name_admin.username) FROM nx_admin name_admin WHERE name_admin.id=sender_id) ELSE sender_name END AS senderName,
              content,
              created_at AS createdAt
            FROM nx_support_ticket_message
            WHERE is_deleted=0 AND ticket_no=#{ticketNo}
            ORDER BY created_at ASC,id ASC
            """)
    List<SupportTicketMessageView> listByTicketNo(@Param("ticketNo") String ticketNo);

    @Select("""
            SELECT id,ticket_id AS ticketId,ticket_no AS ticketNo,sender_id AS senderId,
                   sender_type AS senderType,CASE WHEN sender_type<>'user' AND EXISTS(SELECT 1 FROM nx_admin login_admin WHERE login_admin.id=sender_id AND login_admin.username=sender_name) THEN (SELECT COALESCE(NULLIF(TRIM(name_admin.nickname),''),name_admin.username) FROM nx_admin name_admin WHERE name_admin.id=sender_id) ELSE sender_name END AS senderName,content,created_at AS createdAt
              FROM nx_support_ticket_message
             WHERE is_deleted=0 AND ticket_no=#{ticketNo} AND sender_type IN ('user','agent')
             ORDER BY created_at ASC,id ASC
            """)
    List<SupportTicketMessageView> listUserVisibleByTicketNo(@Param("ticketNo") String ticketNo);

    @Select("""
            SELECT recent.id,recent.ticket_id AS ticketId,recent.ticket_no AS ticketNo,
                   recent.sender_id AS senderId,recent.sender_type AS senderType,
                   CASE WHEN recent.sender_type<>'user' AND EXISTS(SELECT 1 FROM nx_admin login_admin WHERE login_admin.id=recent.sender_id AND login_admin.username=recent.sender_name) THEN (SELECT COALESCE(NULLIF(TRIM(name_admin.nickname),''),name_admin.username) FROM nx_admin name_admin WHERE name_admin.id=recent.sender_id) ELSE recent.sender_name END AS senderName,recent.content,recent.created_at AS createdAt
              FROM (
                SELECT id,ticket_id,ticket_no,sender_id,sender_type,sender_name,content,created_at
                  FROM nx_support_ticket_message
                 WHERE is_deleted=0 AND ticket_no=#{ticketNo} AND sender_type IN ('user','agent')
                 ORDER BY id DESC LIMIT #{limit}
              ) recent
             ORDER BY recent.id ASC
            """)
    List<SupportTicketMessageView> listRecentUserVisibleByTicketNo(
            @Param("ticketNo") String ticketNo, @Param("limit") int limit);

    @Select("""
            SELECT recent.id,recent.ticket_id AS ticketId,recent.ticket_no AS ticketNo,
                   recent.sender_id AS senderId,recent.sender_type AS senderType,
                   CASE WHEN recent.sender_type<>'user' AND EXISTS(SELECT 1 FROM nx_admin login_admin WHERE login_admin.id=recent.sender_id AND login_admin.username=recent.sender_name) THEN (SELECT COALESCE(NULLIF(TRIM(name_admin.nickname),''),name_admin.username) FROM nx_admin name_admin WHERE name_admin.id=recent.sender_id) ELSE recent.sender_name END AS senderName,recent.content,recent.created_at AS createdAt
              FROM (
                SELECT id,ticket_id,ticket_no,sender_id,sender_type,sender_name,content,created_at
                  FROM nx_support_ticket_message
                 WHERE is_deleted=0 AND ticket_no=#{ticketNo} AND sender_type IN ('user','agent')
                   AND (#{beforeMessageId} IS NULL OR id < #{beforeMessageId})
                 ORDER BY id DESC LIMIT #{limit}
              ) recent
             ORDER BY recent.id ASC
            """)
    List<SupportTicketMessageView> listRecentUserVisibleByTicketNoBefore(
            @Param("ticketNo") String ticketNo, @Param("beforeMessageId") Long beforeMessageId,
            @Param("limit") int limit);

    /** M-53: earliest advisor reply per ticket, so list rows can show the same SLA target as the detail view. */
    @Select("""
            <script>
            SELECT ticket_no AS ticketNo,MIN(created_at) AS firstAgentAt
              FROM nx_support_ticket_message
             WHERE is_deleted=0 AND sender_type='agent'
               AND ticket_no IN <foreach collection="ticketNos" item="no" open="(" separator="," close=")">#{no}</foreach>
             GROUP BY ticket_no
            </script>
            """)
    List<FirstAgentReply> firstAgentReplies(@Param("ticketNos") Collection<String> ticketNos);

    record FirstAgentReply(String ticketNo, LocalDateTime firstAgentAt) { }
}
