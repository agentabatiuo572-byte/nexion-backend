package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.infrastructure.SupportTicketEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Admission reads must observe commits made while waiting for the customer mutex. */
public interface SupportTicketCreationMapper extends BaseMapper<SupportTicketEntity> {
    record CreationTicket(Long id, String ticketNo, String category, String title, LocalDateTime createdAt) {}

    // Closing, archiving or soft deleting a ticket must never restore creation budget.
    @Select("""
            SELECT id, ticket_no, category, title, created_at FROM nx_support_ticket
            WHERE user_id=#{userId} AND created_at > #{since}
            ORDER BY created_at DESC, id DESC FOR UPDATE
            """)
    List<CreationTicket> currentRecent(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    @Select("""
            SELECT ticket_no FROM nx_support_ticket WHERE user_id=#{userId} AND is_deleted=0
            AND status IN ('OPEN','IN_PROGRESS','PENDING_USER')
            ORDER BY created_at DESC, id DESC FOR UPDATE
            """)
    List<String> currentActive(@Param("userId") Long userId);

    // The original body is immutable. Read it explicitly as current, including historical rows.
    @Select("""
            SELECT content FROM nx_support_ticket_message WHERE ticket_id=#{ticketId}
            ORDER BY id ASC LIMIT 1 FOR UPDATE
            """)
    String initialBody(@Param("ticketId") Long ticketId);
}
