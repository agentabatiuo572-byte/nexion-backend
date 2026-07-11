package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.domain.ContentConversationMessageView;
import ffdd.opsconsole.content.infrastructure.ConversationMessageEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;

public interface ConversationMessageMapper extends BaseMapper<ConversationMessageEntity> {
    @Select("""
            SELECT
              id,
              conversation_id AS conversationId,
              msg.conversation_no AS conversationNo,
              sender_id AS senderId,
              sender_type AS senderType,
              sender_name AS senderName,
              content,
              COALESCE(receipt.receipt_status, CASE WHEN msg.sender_type='agent' THEN 'sent' ELSE NULL END) AS receiptStatus,
              msg.created_at AS createdAt
            FROM nx_conversation_message msg
            LEFT JOIN nx_conversation_message_receipt receipt ON receipt.message_id=msg.id
            WHERE msg.is_deleted=0 AND msg.conversation_no=#{conversationNo}
            ORDER BY msg.created_at ASC,msg.id ASC
            """)
    List<ContentConversationMessageView> listByConversationNo(@Param("conversationNo") String conversationNo);

    @Insert("""
            INSERT INTO nx_conversation_message_receipt(message_id,conversation_no,receipt_status,read_by,read_at)
            SELECT id,conversation_no,'read',#{operator},#{now}
              FROM nx_conversation_message
             WHERE is_deleted=0
               AND conversation_no=#{conversationNo}
               AND sender_type='agent'
               AND #{lastSeenMessageId} >= id
            ON DUPLICATE KEY UPDATE receipt_status='read',read_by=#{operator},read_at=#{now},updated_at=NOW()
            """)
    int markAgentMessagesReadThrough(@Param("conversationNo") String conversationNo,
                                     @Param("lastSeenMessageId") Long lastSeenMessageId,
                                     @Param("operator") String operator,
                                     @Param("now") LocalDateTime now);
}
