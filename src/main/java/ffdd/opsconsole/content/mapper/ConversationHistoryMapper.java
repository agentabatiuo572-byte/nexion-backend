package ffdd.opsconsole.content.mapper;

import ffdd.opsconsole.content.domain.ContentConversationMessagePage.*;
import ffdd.opsconsole.content.domain.ContentConversationMessageView;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Options;

/** The service authorizes the current customer on every page. No client-provided customer scope. */
@SuppressWarnings("MybatisPlusBaseMapper")
public interface ConversationHistoryMapper {
    @Select("""
        SELECT p.id FROM nx_admin a
        JOIN nx_admin_role_relation rr ON rr.admin_id=a.id AND rr.is_deleted=0
        JOIN nx_admin_role r ON r.id=rr.role_id AND r.status=1 AND r.is_deleted=0
        JOIN nx_admin_role_permission rp ON rp.role_id=r.id AND rp.is_deleted=0
        JOIN nx_admin_permission p ON p.id=rp.permission_id AND p.status=1 AND p.is_deleted=0
        WHERE a.id=#{actorId} AND a.status=1 AND a.is_deleted=0
          AND p.resource_type='API' AND p.permission_code='service_m3_read'
        ORDER BY rr.id,rp.id,p.id FOR SHARE
        """)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Long> currentReadGrants(@Param("actorId") long actorId);

    record Cursor(long id,LocalDateTime createdAt) { }
    @Select("""
        SELECT m.id,m.created_at createdAt FROM nx_conversation_message m
        JOIN nx_conversation c ON c.conversation_no=m.conversation_no
        WHERE c.user_id=#{customerId} AND c.is_deleted=0 AND c.conversation_type IN ('advisor','support')
          AND m.id=#{messageId} AND m.is_deleted=0
        """)
    Cursor cursor(@Param("customerId") long customerId,@Param("messageId") long messageId);

    @Select("""
        <script>
        SELECT msg.id,msg.conversation_id conversationId,msg.conversation_no conversationNo,
          msg.sender_id senderId,msg.sender_type senderType,
        """+ConversationMessageMapper.SENDER_NAME+"""
          ,msg.content,COALESCE(receipt.receipt_status,CASE WHEN msg.sender_type IN ('agent','user') THEN 'sent' ELSE NULL END) receiptStatus,
          msg.created_at createdAt,h.assignment_id assignmentId,COALESCE(h.kind,'TEXT') kind,COALESCE(h.intent,'SERVICE') intent,
          h.client_message_id clientMessageId,h.attachment_id attachmentId,
          CASE WHEN h.message_id IS NULL THEN 'UNKNOWN' ELSE 'VERIFIED' END authorConfidence,
          DATE_FORMAT(h.committed_at,'%Y-%m-%dT%H:%i:%s.%fZ') committedAt,
          h.sku_id skuId,h.sku_name skuName,h.link_target_json linkTargetJson,
          CASE WHEN h.actor_type='ADMIN' AND h.actor_id=av.admin_id THEN av.avatar_asset_id END senderAvatarAssetId,
          CASE WHEN h.actor_type='ADMIN' AND h.actor_id=av.admin_id THEN av.avatar_version END senderAvatarVersion,
        """+ConversationMessageMapper.TARGET_AVAILABILITY+"""
        FROM (
          SELECT m.id,m.conversation_id,m.conversation_no,m.sender_id,m.sender_type,m.sender_name,m.content,m.created_at
          FROM nx_conversation c JOIN nx_conversation_message m ON m.conversation_no=c.conversation_no
          WHERE c.user_id=#{customerId} AND c.is_deleted=0 AND c.conversation_type IN ('advisor','support') AND m.is_deleted=0
          <if test='beforeId != null'>
            AND (m.created_at &lt; #{beforeAt} OR (m.created_at=#{beforeAt} AND m.id &lt; #{beforeId}))
          </if>
          ORDER BY m.created_at DESC,m.id DESC LIMIT 11
        ) msg
        LEFT JOIN nx_support_human_message h ON h.message_id=msg.id
        LEFT JOIN nx_admin_account_state av ON av.admin_id=msg.sender_id AND av.is_deleted=0 AND msg.sender_type='agent'
        LEFT JOIN nx_conversation_message_receipt receipt ON receipt.message_id=msg.id
        ORDER BY msg.created_at DESC,msg.id DESC
        </script>
        """)
    List<ContentConversationMessageView> page(@Param("customerId") long customerId,@Param("beforeAt") LocalDateTime beforeAt,@Param("beforeId") Long beforeId);

    @Select("""
        SELECT id customerId,CONCAT('U',LPAD(id,GREATEST(8,LENGTH(CAST(id AS CHAR))),'0')) userNo,nickname,
          CASE WHEN phone REGEXP '^[0-9]{7,15}$' THEN CONCAT(SUBSTRING(phone,1,3),'****',SUBSTRING(phone,LENGTH(phone)-3)) ELSE NULL END phoneMasked
        FROM nx_user WHERE id=#{customerId} AND is_deleted=0 AND sandbox=0
        """)
    CustomerIdentity identity(@Param("customerId") long customerId);

    @Select("""
        SELECT c.conversation_no conversationNo,MAX(m.id) throughMessageId
        FROM nx_conversation c JOIN nx_conversation_message m ON m.conversation_no=c.conversation_no
        LEFT JOIN nx_support_reply_cursor r ON r.conversation_no=c.conversation_no
        WHERE c.user_id=#{customerId} AND c.is_deleted=0 AND c.conversation_type IN ('advisor','support')
          AND m.is_deleted=0 AND m.sender_type='user' AND m.id>COALESCE(r.through_message_id,0)
        GROUP BY c.conversation_no ORDER BY c.conversation_no
        """)
    List<ReplyTarget> replyTargets(@Param("customerId") long customerId);

    @Select("""
        <script>SELECT c.conversation_no conversationNo,c.status,c.version,MAX(m.id) lastSeenMessageId,
          (SUM(CASE WHEN r.receipt_status='read' THEN 0 ELSE 1 END)=0) alreadyRead
        FROM nx_conversation c JOIN nx_conversation_message m ON m.conversation_no=c.conversation_no
        LEFT JOIN nx_conversation_message_receipt r ON r.message_id=m.id
        WHERE c.user_id=#{customerId} AND c.is_deleted=0 AND m.is_deleted=0 AND m.sender_type='user' AND m.id IN
          <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>
        GROUP BY c.conversation_no,c.status,c.version ORDER BY c.conversation_no</script>
        """)
    List<ReadContext> readContexts(@Param("customerId") long customerId,@Param("ids") Collection<Long> ids);
}
