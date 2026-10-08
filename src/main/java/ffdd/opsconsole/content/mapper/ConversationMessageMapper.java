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
    String TARGET_AVAILABILITY="CASE WHEN h.kind='SKU' THEN CASE WHEN EXISTS(SELECT 1 FROM nx_product p WHERE p.product_no=h.sku_id AND p.is_deleted=0 AND p.store_visible=1 AND p.price_usdt>0 AND ("+
        ffdd.opsconsole.device.mapper.DeviceCatalogMapper.SKU_STATUS_SQL+")='on' AND "+ffdd.opsconsole.shared.canonical.StorefrontProductPublishGate.PUBLISHABLE_SQL+") THEN 'AVAILABLE' ELSE 'UNAVAILABLE' END "+
        "WHEN h.kind='LINK' THEN CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(h.link_target_json,'$.type')) IN ('HOME','WALLET','SUPPORT') THEN 'AVAILABLE' ELSE 'UNAVAILABLE' END ELSE NULL END AS targetAvailability ";
    @Insert("""
            INSERT INTO nx_conversation_message_receipt(message_id,conversation_no,receipt_status,read_by,read_at)
            SELECT m.id,m.conversation_no,'read',#{operator},#{now}
            FROM nx_conversation_message m
            LEFT JOIN nx_conversation_message_receipt r ON r.message_id=m.id
            WHERE m.conversation_no=#{conversationNo} AND m.is_deleted=0 AND m.sender_type='user'
              AND m.id<=#{lastSeenMessageId} AND (r.message_id IS NULL OR r.receipt_status<>'read')
            ON DUPLICATE KEY UPDATE receipt_status='read',read_by=#{operator},read_at=#{now},updated_at=NOW()
            """)
    int markUserMessagesReadThrough(@Param("conversationNo") String conversationNo,
            @Param("lastSeenMessageId") Long lastSeenMessageId, @Param("operator") String operator,
            @Param("now") LocalDateTime now);
    @Select("""
            SELECT
              msg.id,
              msg.conversation_id AS conversationId,
              msg.conversation_no AS conversationNo,
              sender_id AS senderId,
              sender_type AS senderType,
              sender_name AS senderName,
              content,
              COALESCE(receipt.receipt_status, CASE WHEN msg.sender_type IN ('agent','user') THEN 'sent' ELSE NULL END) AS receiptStatus,
              msg.created_at AS createdAt,
              h.assignment_id AS assignmentId,COALESCE(h.kind,'TEXT') AS kind,COALESCE(h.intent,'SERVICE') AS intent,
              h.client_message_id AS clientMessageId,h.attachment_id AS attachmentId,
              CASE WHEN h.message_id IS NULL THEN 'UNKNOWN' ELSE 'VERIFIED' END AS authorConfidence,
              DATE_FORMAT(h.committed_at,'%Y-%m-%dT%H:%i:%s.%fZ') AS committedAt,
              h.sku_id AS skuId,h.sku_name AS skuName,h.link_target_json AS linkTargetJson,
              CASE WHEN h.actor_type='ADMIN' AND h.actor_id=av.admin_id THEN av.avatar_asset_id END AS senderAvatarAssetId,
              CASE WHEN h.actor_type='ADMIN' AND h.actor_id=av.admin_id THEN av.avatar_version END AS senderAvatarVersion,
            """ + TARGET_AVAILABILITY + """
            FROM nx_conversation_message msg
            LEFT JOIN nx_support_human_message h ON h.message_id=msg.id
              LEFT JOIN nx_admin_account_state av ON av.admin_id=msg.sender_id AND av.is_deleted=0 AND msg.sender_type='agent'
              LEFT JOIN nx_conversation_message_receipt receipt ON receipt.message_id=msg.id
            WHERE msg.is_deleted=0 AND msg.conversation_no=#{conversationNo}
            ORDER BY msg.created_at ASC,msg.id ASC
            """)
    List<ContentConversationMessageView> listByConversationNo(@Param("conversationNo") String conversationNo);

    @Select("""
            SELECT msg.id,msg.conversation_id AS conversationId,msg.conversation_no AS conversationNo,
                   sender_id AS senderId,sender_type AS senderType,sender_name AS senderName,content,
                   COALESCE(receipt.receipt_status, CASE WHEN msg.sender_type IN ('agent','user') THEN 'sent' ELSE NULL END) AS receiptStatus,
                   msg.created_at AS createdAt,
              h.assignment_id AS assignmentId,COALESCE(h.kind,'TEXT') AS kind,COALESCE(h.intent,'SERVICE') AS intent,
              h.client_message_id AS clientMessageId,h.attachment_id AS attachmentId,
              CASE WHEN h.message_id IS NULL THEN 'UNKNOWN' ELSE 'VERIFIED' END AS authorConfidence,
              DATE_FORMAT(h.committed_at,'%Y-%m-%dT%H:%i:%s.%fZ') AS committedAt,
              h.sku_id AS skuId,h.sku_name AS skuName,h.link_target_json AS linkTargetJson,
              CASE WHEN h.actor_type='ADMIN' AND h.actor_id=av.admin_id THEN av.avatar_asset_id END AS senderAvatarAssetId,
              CASE WHEN h.actor_type='ADMIN' AND h.actor_id=av.admin_id THEN av.avatar_version END AS senderAvatarVersion,
            """ + TARGET_AVAILABILITY + """
              FROM nx_conversation_message msg
              LEFT JOIN nx_support_human_message h ON h.message_id=msg.id
              LEFT JOIN nx_admin_account_state av ON av.admin_id=msg.sender_id AND av.is_deleted=0 AND msg.sender_type='agent'
              LEFT JOIN nx_conversation_message_receipt receipt ON receipt.message_id=msg.id
             WHERE msg.is_deleted=0 AND msg.conversation_no=#{conversationNo}
               AND msg.sender_type IN ('user','agent')
             ORDER BY msg.created_at ASC,msg.id ASC
            """)
    List<ContentConversationMessageView> listUserVisibleByConversationNo(@Param("conversationNo") String conversationNo);

    @Select("""
            <script>
            SELECT recent.id,recent.conversation_id AS conversationId,recent.conversation_no AS conversationNo,
                   recent.sender_id AS senderId,recent.sender_type AS senderType,recent.sender_name AS senderName,
                   recent.content,
                   COALESCE(receipt.receipt_status, CASE WHEN recent.sender_type IN ('agent','user') THEN 'sent' ELSE NULL END) AS receiptStatus,
                   recent.created_at AS createdAt,
              h.assignment_id AS assignmentId,COALESCE(h.kind,'TEXT') AS kind,COALESCE(h.intent,'SERVICE') AS intent,
              h.client_message_id AS clientMessageId,h.attachment_id AS attachmentId,
              CASE WHEN h.message_id IS NULL THEN 'UNKNOWN' ELSE 'VERIFIED' END AS authorConfidence,
              DATE_FORMAT(h.committed_at,'%Y-%m-%dT%H:%i:%s.%fZ') AS committedAt,
              h.sku_id AS skuId,h.sku_name AS skuName,h.link_target_json AS linkTargetJson,
              CASE WHEN h.actor_type='ADMIN' AND h.actor_id=av.admin_id THEN av.avatar_asset_id END AS senderAvatarAssetId,
              CASE WHEN h.actor_type='ADMIN' AND h.actor_id=av.admin_id THEN av.avatar_version END AS senderAvatarVersion,
            """ + TARGET_AVAILABILITY + """
              FROM (
                SELECT id,conversation_id,conversation_no,sender_id,sender_type,sender_name,content,created_at
                  FROM nx_conversation_message
                 WHERE is_deleted=0 AND conversation_no=#{conversationNo} AND sender_type IN ('user','agent')
                 ORDER BY id DESC LIMIT #{limit}
                 <if test="currentRead">FOR SHARE</if>
              ) recent
              LEFT JOIN nx_support_human_message h ON h.message_id=recent.id
              LEFT JOIN nx_admin_account_state av ON av.admin_id=recent.sender_id AND av.is_deleted=0 AND recent.sender_type='agent'
              LEFT JOIN nx_conversation_message_receipt receipt ON receipt.message_id=recent.id
             ORDER BY recent.id ASC
             <if test="currentRead">FOR SHARE</if>
             </script>
            """)
    List<ContentConversationMessageView> selectRecentVisible(
            @Param("conversationNo") String conversationNo, @Param("limit") int limit, @Param("currentRead") boolean currentRead);

    default List<ContentConversationMessageView> listRecentUserVisibleByConversationNo(String no,int limit) { return selectRecentVisible(no,limit,false); }
    default List<ContentConversationMessageView> listCurrentRecentUserVisibleByConversationNo(String no,int limit) { return selectRecentVisible(no,limit,true); }

    @Select("""
            SELECT recent.id,recent.conversation_id AS conversationId,recent.conversation_no AS conversationNo,
                   recent.sender_id AS senderId,recent.sender_type AS senderType,recent.sender_name AS senderName,
                   recent.content,
                   COALESCE(receipt.receipt_status, CASE WHEN recent.sender_type IN ('agent','user') THEN 'sent' ELSE NULL END) AS receiptStatus,
                   recent.created_at AS createdAt,
              h.assignment_id AS assignmentId,COALESCE(h.kind,'TEXT') AS kind,COALESCE(h.intent,'SERVICE') AS intent,
              h.client_message_id AS clientMessageId,h.attachment_id AS attachmentId,
              CASE WHEN h.message_id IS NULL THEN 'UNKNOWN' ELSE 'VERIFIED' END AS authorConfidence,
              DATE_FORMAT(h.committed_at,'%Y-%m-%dT%H:%i:%s.%fZ') AS committedAt,
              h.sku_id AS skuId,h.sku_name AS skuName,h.link_target_json AS linkTargetJson,
              CASE WHEN h.actor_type='ADMIN' AND h.actor_id=av.admin_id THEN av.avatar_asset_id END AS senderAvatarAssetId,
              CASE WHEN h.actor_type='ADMIN' AND h.actor_id=av.admin_id THEN av.avatar_version END AS senderAvatarVersion,
            """ + TARGET_AVAILABILITY + """
              FROM (
                SELECT id,conversation_id,conversation_no,sender_id,sender_type,sender_name,content,created_at
                  FROM nx_conversation_message
                 WHERE is_deleted=0 AND conversation_no=#{conversationNo} AND sender_type IN ('user','agent')
                   AND (#{beforeMessageId} IS NULL OR id < #{beforeMessageId})
                 ORDER BY id DESC LIMIT #{limit}
              ) recent
              LEFT JOIN nx_support_human_message h ON h.message_id=recent.id
              LEFT JOIN nx_admin_account_state av ON av.admin_id=recent.sender_id AND av.is_deleted=0 AND recent.sender_type='agent'
              LEFT JOIN nx_conversation_message_receipt receipt ON receipt.message_id=recent.id
             ORDER BY recent.id ASC
            """)
    List<ContentConversationMessageView> listRecentUserVisibleByConversationNoBefore(
            @Param("conversationNo") String conversationNo, @Param("beforeMessageId") Long beforeMessageId,
            @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM nx_conversation_message msg
              LEFT JOIN nx_support_human_message h ON h.message_id=msg.id
              LEFT JOIN nx_admin_account_state av ON av.admin_id=msg.sender_id AND av.is_deleted=0 AND msg.sender_type='agent'
              LEFT JOIN nx_conversation_message_receipt receipt ON receipt.message_id=msg.id
             WHERE msg.is_deleted=0 AND msg.conversation_no=#{conversationNo} AND msg.sender_type='agent'
               AND (receipt.message_id IS NULL OR receipt.receipt_status&lt;&gt;'read')
            <if test="currentRead">FOR SHARE</if>
            </script>
            """)
    int selectUnreadUserVisibleAgentMessages(@Param("conversationNo") String conversationNo,@Param("currentRead") boolean currentRead);

    default int countUnreadUserVisibleAgentMessages(String no) { return selectUnreadUserVisibleAgentMessages(no,false); }
    default int countCurrentUnreadUserVisibleAgentMessages(String no) { return selectUnreadUserVisibleAgentMessages(no,true); }

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
