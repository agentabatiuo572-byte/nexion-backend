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

    @Select("""
            SELECT id,conversation_id AS conversationId,msg.conversation_no AS conversationNo,
                   sender_id AS senderId,sender_type AS senderType,sender_name AS senderName,content,
                   COALESCE(receipt.receipt_status, CASE WHEN msg.sender_type='agent' THEN 'sent' ELSE NULL END) AS receiptStatus,
                   msg.created_at AS createdAt
              FROM nx_conversation_message msg
              LEFT JOIN nx_conversation_message_receipt receipt ON receipt.message_id=msg.id
             WHERE msg.is_deleted=0 AND msg.conversation_no=#{conversationNo}
               AND msg.sender_type IN ('user','agent')
             ORDER BY msg.created_at ASC,msg.id ASC
            """)
    List<ContentConversationMessageView> listUserVisibleByConversationNo(@Param("conversationNo") String conversationNo);

    @Select("""
            SELECT recent.id,recent.conversation_id AS conversationId,recent.conversation_no AS conversationNo,
                   recent.sender_id AS senderId,recent.sender_type AS senderType,recent.sender_name AS senderName,
                   recent.content,
                   COALESCE(receipt.receipt_status, CASE WHEN recent.sender_type='agent' THEN 'sent' ELSE NULL END) AS receiptStatus,
                   recent.created_at AS createdAt
              FROM (
                SELECT id,conversation_id,conversation_no,sender_id,sender_type,sender_name,content,created_at
                  FROM nx_conversation_message
                 WHERE is_deleted=0 AND conversation_no=#{conversationNo} AND sender_type IN ('user','agent')
                 ORDER BY id DESC LIMIT #{limit}
              ) recent
              LEFT JOIN nx_conversation_message_receipt receipt ON receipt.message_id=recent.id
             ORDER BY recent.id ASC
            """)
    List<ContentConversationMessageView> listRecentUserVisibleByConversationNo(
            @Param("conversationNo") String conversationNo, @Param("limit") int limit);

    @Select("""
            SELECT recent.id,recent.conversation_id AS conversationId,recent.conversation_no AS conversationNo,
                   recent.sender_id AS senderId,recent.sender_type AS senderType,recent.sender_name AS senderName,
                   recent.content,
                   COALESCE(receipt.receipt_status, CASE WHEN recent.sender_type='agent' THEN 'sent' ELSE NULL END) AS receiptStatus,
                   recent.created_at AS createdAt
              FROM (
                SELECT id,conversation_id,conversation_no,sender_id,sender_type,sender_name,content,created_at
                  FROM nx_conversation_message
                 WHERE is_deleted=0 AND conversation_no=#{conversationNo} AND sender_type IN ('user','agent')
                   AND (#{beforeMessageId} IS NULL OR id < #{beforeMessageId})
                 ORDER BY id DESC LIMIT #{limit}
              ) recent
              LEFT JOIN nx_conversation_message_receipt receipt ON receipt.message_id=recent.id
             ORDER BY recent.id ASC
            """)
    List<ContentConversationMessageView> listRecentUserVisibleByConversationNoBefore(
            @Param("conversationNo") String conversationNo, @Param("beforeMessageId") Long beforeMessageId,
            @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
              FROM nx_conversation_message msg
              LEFT JOIN nx_conversation_message_receipt receipt ON receipt.message_id=msg.id
             WHERE msg.is_deleted=0 AND msg.conversation_no=#{conversationNo} AND msg.sender_type='agent'
               AND (receipt.message_id IS NULL OR receipt.receipt_status<>'read')
            """)
    int countUnreadUserVisibleAgentMessages(@Param("conversationNo") String conversationNo);

    @Insert("""
            INSERT INTO nx_conversation_message_receipt(message_id,conversation_no,receipt_status,read_by,read_at)
            SELECT msg.id,msg.conversation_no,'read',#{operator},#{now}
             FROM nx_conversation_message msg
              LEFT JOIN nx_conversation_message_receipt existing ON existing.message_id=msg.id
              JOIN nx_conversation conversation
                ON conversation.conversation_no=msg.conversation_no
               AND conversation.is_deleted=0
               AND conversation.status=UPPER(#{expectedStatus})
               AND conversation.version=#{expectedVersion}
             WHERE msg.is_deleted=0
               AND msg.conversation_no=#{conversationNo}
               AND msg.sender_type='agent'
               AND #{lastSeenMessageId} >= msg.id
               AND (existing.message_id IS NULL OR existing.receipt_status<>'read')
            ON DUPLICATE KEY UPDATE receipt_status='read',read_by=#{operator},read_at=#{now},updated_at=NOW()
            """)
    int markAgentMessagesReadThrough(@Param("conversationNo") String conversationNo,
                                     @Param("lastSeenMessageId") Long lastSeenMessageId,
                                     @Param("operator") String operator,
                                     @Param("now") LocalDateTime now,
                                     @Param("expectedStatus") String expectedStatus,
                                     @Param("expectedVersion") Long expectedVersion);
}
