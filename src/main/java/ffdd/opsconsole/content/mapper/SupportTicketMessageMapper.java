package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.domain.SupportTicketMessageView;
import ffdd.opsconsole.content.infrastructure.SupportTicketMessageEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SupportTicketMessageMapper extends BaseMapper<SupportTicketMessageEntity> {
    @Select("""
            SELECT
              id,
              ticket_id AS ticketId,
              ticket_no AS ticketNo,
              sender_id AS senderId,
              sender_type AS senderType,
              sender_name AS senderName,
              content,
              created_at AS createdAt
            FROM nx_support_ticket_message
            WHERE is_deleted=0 AND ticket_no=#{ticketNo}
            ORDER BY created_at ASC,id ASC
            """)
    List<SupportTicketMessageView> listByTicketNo(@Param("ticketNo") String ticketNo);
}
