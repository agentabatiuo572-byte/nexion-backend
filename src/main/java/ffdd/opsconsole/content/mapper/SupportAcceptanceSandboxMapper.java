package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.domain.SupportAcceptanceSandboxObservationWindow;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Acceptance-only support storage mapper. */
@Mapper
public interface SupportAcceptanceSandboxMapper extends BaseMapper<Object> {
    @Select("SELECT sandbox FROM nx_user WHERE id=#{accountId} AND is_deleted=0")
    Integer sandboxUser(@Param("accountId") Long accountId);
    @Insert("""
            INSERT IGNORE INTO nx_support_acceptance_sandbox_run(run_id,account_id,source,source_environment,status,created_at,updated_at)
            VALUES(#{runId},#{accountId},'mock','SANDBOX','OPEN',#{now},#{now})
            """)
    int ensureRun(@Param("runId") String runId, @Param("accountId") Long accountId, @Param("now") LocalDateTime now);

    @Select("""
            SELECT ticket_no ticketNo,category,priority,title,LOWER(status) status,owner_agent_name assignedAdminName,
                   last_message_at lastMessageAt,version,created_at createdAt,updated_at updatedAt,
                   (SELECT COUNT(*) FROM nx_support_acceptance_sandbox_ticket_message m WHERE m.ticket_no=t.ticket_no) messageCount,0 userUnreadCount
              FROM nx_support_acceptance_sandbox_ticket t WHERE account_id=#{accountId} AND run_id=#{runId} ORDER BY updated_at DESC
            """)
    List<Map<String,Object>> tickets(@Param("runId") String runId, @Param("accountId") Long accountId);
    @Select("SELECT ticket_no ticketNo,account_id accountId,category,priority,title,LOWER(status) status,owner_agent_name assignedAdminName,last_message_at lastMessageAt,version,created_at createdAt,updated_at updatedAt FROM nx_support_acceptance_sandbox_ticket WHERE run_id=#{runId} AND source='mock' AND source_environment='SANDBOX' ORDER BY updated_at DESC")
    List<Map<String,Object>> adminTickets(@Param("runId") String runId);
    @Select("SELECT ticket_no ticketNo,account_id accountId,category,priority,title,LOWER(status) status,owner_agent_name assignedAdminName,last_message_at lastMessageAt,version,created_at createdAt,updated_at updatedAt FROM nx_support_acceptance_sandbox_ticket WHERE ticket_no=#{id} AND run_id=#{runId} AND source='mock' AND source_environment='SANDBOX'")
    Map<String,Object> adminTicket(@Param("runId") String runId,@Param("id") String id);

    @Select("""
            SELECT ticket_no ticketNo,category,priority,title,LOWER(status) status,owner_agent_name assignedAdminName,
                   last_message_at lastMessageAt,version,created_at createdAt,updated_at updatedAt,
                   (SELECT COUNT(*) FROM nx_support_acceptance_sandbox_ticket_message m WHERE m.ticket_no=t.ticket_no) messageCount,0 userUnreadCount
              FROM nx_support_acceptance_sandbox_ticket t WHERE ticket_no=#{id} AND account_id=#{accountId} AND run_id=#{runId}
            """)
    Map<String,Object> ticket(@Param("runId") String runId,@Param("accountId") Long accountId,@Param("id") String id);
    @Select("SELECT id,ticket_no ticketNo,sender_type senderType,sender_name senderName,content,created_at createdAt FROM nx_support_acceptance_sandbox_ticket_message WHERE ticket_no=#{id} AND account_id=#{accountId} AND run_id=#{runId} ORDER BY id")
    List<Map<String,Object>> ticketMessages(@Param("runId") String runId,@Param("accountId") Long accountId,@Param("id") String id);
    @Insert("INSERT INTO nx_support_acceptance_sandbox_ticket(ticket_no,run_id,account_id,category,priority,title,status,owner_agent_name,last_message_at,version,source,source_environment,created_at,updated_at) VALUES(#{id},#{runId},#{accountId},#{category},'NORMAL',#{title},'OPEN','Unassigned',#{now},0,'mock','SANDBOX',#{now},#{now})")
    int createTicket(@Param("id") String id,@Param("runId") String runId,@Param("accountId") Long accountId,@Param("category") String category,@Param("title") String title,@Param("now") LocalDateTime now);
    @Insert("INSERT INTO nx_support_acceptance_sandbox_ticket_message(ticket_no,run_id,account_id,sender_type,sender_name,content,client_message_id,source,source_environment,created_at) VALUES(#{id},#{runId},#{accountId},'user','User',#{body},#{key},'mock','SANDBOX',#{now})")
    int ticketMessage(@Param("id") String id,@Param("runId") String runId,@Param("accountId") Long accountId,@Param("body") String body,@Param("key") String key,@Param("now") LocalDateTime now);
    @Update("UPDATE nx_support_acceptance_sandbox_ticket SET status=#{next},version=version+1,updated_at=#{now},last_message_at=#{now} WHERE ticket_no=#{id} AND account_id=#{accountId} AND run_id=#{runId} AND status=#{status} AND version=#{version} AND source='mock' AND source_environment='SANDBOX'")
    int ticketCas(@Param("id") String id,@Param("runId") String runId,@Param("accountId") Long accountId,@Param("status") String status,@Param("version") Long version,@Param("next") String next,@Param("now") LocalDateTime now);
    @Insert("INSERT INTO nx_support_acceptance_sandbox_ticket_message(ticket_no,run_id,account_id,sender_type,sender_name,content,client_message_id,source,source_environment,created_at) VALUES(#{id},#{runId},#{accountId},'agent',#{agent},#{body},#{key},'mock','SANDBOX',#{now})")
    int agentTicketMessage(@Param("id") String id,@Param("runId") String runId,@Param("accountId") Long accountId,@Param("agent") String agent,@Param("body") String body,@Param("key") String key,@Param("now") LocalDateTime now);

    @Select("SELECT conversation_no conversationNo,conversation_type conversationType,LOWER(status) status,owner_agent_name ownerAgentName,unread_count unreadCount,last_message lastMessage,last_message_at lastMessageAt,version,updated_at updatedAt FROM nx_support_acceptance_sandbox_conversation WHERE account_id=#{accountId} AND run_id=#{runId} ORDER BY updated_at DESC")
    List<Map<String,Object>> conversations(@Param("runId") String runId,@Param("accountId") Long accountId);
    @Select("SELECT conversation_no conversationNo,account_id accountId,conversation_type conversationType,LOWER(status) status,owner_agent_name ownerAgentName,unread_count unreadCount,last_message lastMessage,last_message_at lastMessageAt,version,updated_at updatedAt FROM nx_support_acceptance_sandbox_conversation WHERE run_id=#{runId} AND source='mock' AND source_environment='SANDBOX' ORDER BY updated_at DESC")
    List<Map<String,Object>> adminConversations(@Param("runId") String runId);
    @Select("SELECT conversation_no conversationNo,conversation_type conversationType,LOWER(status) status,owner_agent_name ownerAgentName,unread_count unreadCount,last_message lastMessage,last_message_at lastMessageAt,version,updated_at updatedAt FROM nx_support_acceptance_sandbox_conversation WHERE conversation_no=#{id} AND account_id=#{accountId} AND run_id=#{runId}")
    Map<String,Object> conversation(@Param("runId") String runId,@Param("accountId") Long accountId,@Param("id") String id);
    @Select("SELECT m.id,m.sender_type senderType,m.sender_name senderName,m.content,m.created_at createdAt,COALESCE(r.receipt_status,CASE WHEN m.sender_type='agent' THEN 'sent' END) receiptStatus FROM nx_support_acceptance_sandbox_conversation_message m LEFT JOIN nx_support_acceptance_sandbox_receipt r ON r.message_id=m.id WHERE m.conversation_no=#{id} AND m.account_id=#{accountId} AND m.run_id=#{runId} ORDER BY m.id")
    List<Map<String,Object>> conversationMessages(@Param("runId") String runId,@Param("accountId") Long accountId,@Param("id") String id);
    @Select("SELECT COUNT(*) FROM nx_support_acceptance_sandbox_conversation_message WHERE id=#{lastSeen} AND conversation_no=#{id} AND account_id=#{accountId} AND run_id=#{runId} AND sender_type='agent' AND source='mock' AND source_environment='SANDBOX'")
    int agentMessageExists(@Param("runId") String runId,@Param("accountId") Long accountId,@Param("id") String id,@Param("lastSeen") Long lastSeen);
    @Insert("INSERT INTO nx_support_acceptance_sandbox_conversation(conversation_no,run_id,account_id,conversation_type,status,owner_agent_name,unread_count,last_message,last_message_at,version,source,source_environment,created_at,updated_at) VALUES(#{id},#{runId},#{accountId},#{type},'OPEN','Unassigned',0,#{body},#{now},0,'mock','SANDBOX',#{now},#{now})")
    int createConversation(@Param("id") String id,@Param("runId") String runId,@Param("accountId") Long accountId,@Param("type") String type,@Param("body") String body,@Param("now") LocalDateTime now);
    @Insert("INSERT INTO nx_support_acceptance_sandbox_conversation_message(conversation_no,run_id,account_id,sender_type,sender_name,content,client_message_id,source,source_environment,created_at) VALUES(#{id},#{runId},#{accountId},'user','User',#{body},#{key},'mock','SANDBOX',#{now})")
    int conversationMessage(@Param("id") String id,@Param("runId") String runId,@Param("accountId") Long accountId,@Param("body") String body,@Param("key") String key,@Param("now") LocalDateTime now);
    @Insert("INSERT INTO nx_support_acceptance_sandbox_conversation_message(conversation_no,run_id,account_id,sender_type,sender_name,content,client_message_id,source,source_environment,created_at) VALUES(#{id},#{runId},#{accountId},'agent',#{agent},#{body},#{key},'mock','SANDBOX',#{now})")
    int agentConversationMessage(@Param("id") String id,@Param("runId") String runId,@Param("accountId") Long accountId,@Param("agent") String agent,@Param("body") String body,@Param("key") String key,@Param("now") LocalDateTime now);
    @Update("UPDATE nx_support_acceptance_sandbox_conversation SET status=#{next},last_message=#{body},last_message_at=#{now},version=version+1,updated_at=#{now} WHERE conversation_no=#{id} AND account_id=#{accountId} AND run_id=#{runId} AND status=#{status} AND version=#{version} AND source='mock' AND source_environment='SANDBOX'")
    int conversationCas(@Param("id") String id,@Param("runId") String runId,@Param("accountId") Long accountId,@Param("status") String status,@Param("version") Long version,@Param("next") String next,@Param("body") String body,@Param("now") LocalDateTime now);
    @Update("UPDATE nx_support_acceptance_sandbox_conversation SET unread_count=unread_count+1,last_message=#{body},last_message_at=#{now},version=version+1,updated_at=#{now} WHERE conversation_no=#{id} AND account_id=#{accountId} AND run_id=#{runId} AND status=#{status} AND version=#{version} AND source='mock' AND source_environment='SANDBOX'")
    int agentReplyCas(@Param("id") String id,@Param("runId") String runId,@Param("accountId") Long accountId,@Param("status") String status,@Param("version") Long version,@Param("body") String body,@Param("now") LocalDateTime now);
    @Update("UPDATE nx_support_acceptance_sandbox_conversation SET unread_count=0,updated_at=#{now} WHERE conversation_no=#{id} AND account_id=#{accountId} AND run_id=#{runId} AND status=#{status} AND version=#{version} AND source='mock' AND source_environment='SANDBOX'")
    int readHeaderCas(@Param("id") String id,@Param("runId") String runId,@Param("accountId") Long accountId,@Param("status") String status,@Param("version") Long version,@Param("now") LocalDateTime now);
    @Update("UPDATE nx_support_acceptance_sandbox_conversation SET owner_agent_id=#{agentId},owner_agent_name=#{agentName},status='TRANSFERRED',version=version+1,updated_at=#{now} WHERE conversation_no=#{id} AND account_id=#{accountId} AND run_id=#{runId} AND status=#{status} AND version=#{version} AND source='mock' AND source_environment='SANDBOX'")
    int transferCas(@Param("id") String id,@Param("runId") String runId,@Param("accountId") Long accountId,@Param("status") String status,@Param("version") Long version,@Param("agentId") String agentId,@Param("agentName") String agentName,@Param("now") LocalDateTime now);
    @Update("""
            INSERT INTO nx_support_acceptance_sandbox_receipt(message_id,conversation_no,run_id,account_id,receipt_status,read_by,read_at,source,source_environment,created_at,updated_at)
            SELECT m.id,m.conversation_no,m.run_id,m.account_id,'read',#{reader},#{now},'mock','SANDBOX',#{now},#{now}
            FROM nx_support_acceptance_sandbox_conversation_message m JOIN nx_support_acceptance_sandbox_conversation c ON c.conversation_no=m.conversation_no AND c.status=#{status} AND c.version=#{version}
            WHERE m.conversation_no=#{id} AND m.account_id=#{accountId} AND m.run_id=#{runId} AND m.sender_type='agent' AND m.id<=#{lastSeen}
              AND EXISTS (SELECT 1 FROM nx_support_acceptance_sandbox_conversation_message target
                           WHERE target.id=#{lastSeen} AND target.conversation_no=#{id} AND target.account_id=#{accountId}
                             AND target.run_id=#{runId} AND target.sender_type='agent'
                             AND target.source='mock' AND target.source_environment='SANDBOX')
            ON DUPLICATE KEY UPDATE receipt_status='read',read_by=#{reader},read_at=#{now},updated_at=#{now}
            """)
    int readCas(@Param("id") String id,@Param("runId") String runId,@Param("accountId") Long accountId,@Param("lastSeen") Long lastSeen,@Param("status") String status,@Param("version") Long version,@Param("reader") String reader,@Param("now") LocalDateTime now);

    @Select("SELECT command_type commandType,result_type resultType,result_id resultId,payload_hash payloadHash,result_json resultJson FROM nx_support_acceptance_sandbox_idempotency WHERE command_key=#{key} AND account_id=#{accountId} AND run_id=#{runId}")
    Map<String,Object> command(@Param("key") String key,@Param("runId") String runId,@Param("accountId") Long accountId);
    @Select("SELECT command_type commandType,result_type resultType,result_id resultId,payload_hash payloadHash,result_json resultJson FROM nx_support_acceptance_sandbox_idempotency WHERE command_key=#{key} AND run_id=#{runId} AND source='mock' AND source_environment='SANDBOX'")
    Map<String,Object> adminCommand(@Param("key") String key,@Param("runId") String runId);
    @Insert("INSERT INTO nx_support_acceptance_sandbox_idempotency(command_key,run_id,account_id,command_type,business_key,reason,payload_hash,result_json,result_type,result_id,status,source,source_environment,created_at,updated_at) VALUES(#{key},#{runId},#{accountId},#{type},#{business},#{reason},#{hash},CAST(#{resultJson} AS JSON),#{resultType},#{resultId},'COMMITTED','mock','SANDBOX',#{now},#{now})")
    int commandInsert(@Param("key") String key,@Param("runId") String runId,@Param("accountId") Long accountId,@Param("type") String type,@Param("business") String business,@Param("reason") String reason,@Param("hash") String hash,@Param("resultJson") String resultJson,@Param("resultType") String resultType,@Param("resultId") String resultId,@Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(*) facts,COUNT(DISTINCT f.account_id) sandboxAccounts,
                   DATE_SUB(MIN(f.at), INTERVAL 1 MINUTE) fromAt,DATE_ADD(MAX(f.at), INTERVAL 1 MINUTE) toAt
            FROM (
              SELECT account_id,created_at at FROM nx_support_acceptance_sandbox_run WHERE run_id=#{runId}
              UNION ALL SELECT account_id,created_at FROM nx_support_acceptance_sandbox_ticket WHERE run_id=#{runId}
              UNION ALL SELECT account_id,created_at FROM nx_support_acceptance_sandbox_ticket_message WHERE run_id=#{runId}
              UNION ALL SELECT account_id,created_at FROM nx_support_acceptance_sandbox_conversation WHERE run_id=#{runId}
              UNION ALL SELECT account_id,created_at FROM nx_support_acceptance_sandbox_conversation_message WHERE run_id=#{runId}
              UNION ALL SELECT account_id,created_at FROM nx_support_acceptance_sandbox_receipt WHERE run_id=#{runId}
              UNION ALL SELECT account_id,created_at FROM nx_support_acceptance_sandbox_idempotency WHERE run_id=#{runId}
            ) f JOIN nx_user u ON u.id=f.account_id AND u.sandbox=1 AND u.is_deleted=0
            """)
    SupportAcceptanceSandboxObservationWindow observationWindow(@Param("runId") String runId);

    @Select("SELECT COUNT(*) FROM nx_support_ticket WHERE user_id IN (SELECT r.account_id FROM nx_support_acceptance_sandbox_run r JOIN nx_user u ON u.id=r.account_id AND u.sandbox=1 AND u.is_deleted=0 WHERE r.run_id=#{runId}) AND (created_at BETWEEN #{fromAt} AND #{toAt} OR updated_at BETWEEN #{fromAt} AND #{toAt})")
    int productionTicketDelta(@Param("runId") String runId,@Param("fromAt") LocalDateTime fromAt,@Param("toAt") LocalDateTime toAt);
    @Select("SELECT COUNT(*) FROM nx_support_ticket_message m JOIN nx_support_ticket t ON t.ticket_no=m.ticket_no WHERE t.user_id IN (SELECT r.account_id FROM nx_support_acceptance_sandbox_run r JOIN nx_user u ON u.id=r.account_id AND u.sandbox=1 AND u.is_deleted=0 WHERE r.run_id=#{runId}) AND (m.created_at BETWEEN #{fromAt} AND #{toAt} OR m.updated_at BETWEEN #{fromAt} AND #{toAt})")
    int productionTicketMessageDelta(@Param("runId") String runId,@Param("fromAt") LocalDateTime fromAt,@Param("toAt") LocalDateTime toAt);
    @Select("SELECT COUNT(*) FROM nx_conversation WHERE user_id IN (SELECT r.account_id FROM nx_support_acceptance_sandbox_run r JOIN nx_user u ON u.id=r.account_id AND u.sandbox=1 AND u.is_deleted=0 WHERE r.run_id=#{runId}) AND (created_at BETWEEN #{fromAt} AND #{toAt} OR updated_at BETWEEN #{fromAt} AND #{toAt})")
    int productionConversationDelta(@Param("runId") String runId,@Param("fromAt") LocalDateTime fromAt,@Param("toAt") LocalDateTime toAt);
    @Select("SELECT COUNT(*) FROM nx_conversation_message m JOIN nx_conversation c ON c.conversation_no=m.conversation_no WHERE c.user_id IN (SELECT r.account_id FROM nx_support_acceptance_sandbox_run r JOIN nx_user u ON u.id=r.account_id AND u.sandbox=1 AND u.is_deleted=0 WHERE r.run_id=#{runId}) AND (m.created_at BETWEEN #{fromAt} AND #{toAt} OR m.updated_at BETWEEN #{fromAt} AND #{toAt})")
    int productionConversationMessageDelta(@Param("runId") String runId,@Param("fromAt") LocalDateTime fromAt,@Param("toAt") LocalDateTime toAt);
    @Select("SELECT COUNT(*) FROM nx_conversation_message_receipt r JOIN nx_conversation c ON c.conversation_no=r.conversation_no WHERE c.user_id IN (SELECT sr.account_id FROM nx_support_acceptance_sandbox_run sr JOIN nx_user u ON u.id=sr.account_id AND u.sandbox=1 AND u.is_deleted=0 WHERE sr.run_id=#{runId}) AND (r.created_at BETWEEN #{fromAt} AND #{toAt} OR r.updated_at BETWEEN #{fromAt} AND #{toAt})")
    int productionReceiptDelta(@Param("runId") String runId,@Param("fromAt") LocalDateTime fromAt,@Param("toAt") LocalDateTime toAt);
    @Select("""
            SELECT COUNT(*) FROM nx_audit_log a
             WHERE a.created_at BETWEEN #{fromAt} AND #{toAt}
               AND (
                    a.user_id IN (SELECT r.account_id FROM nx_support_acceptance_sandbox_run r JOIN nx_user u ON u.id=r.account_id AND u.sandbox=1 AND u.is_deleted=0 WHERE r.run_id=#{runId})
                 OR a.resource_id IN (SELECT ticket_no FROM nx_support_acceptance_sandbox_ticket WHERE run_id=#{runId})
                 OR a.biz_no IN (SELECT ticket_no FROM nx_support_acceptance_sandbox_ticket WHERE run_id=#{runId})
                 OR a.resource_id IN (SELECT conversation_no FROM nx_support_acceptance_sandbox_conversation WHERE run_id=#{runId})
                 OR a.biz_no IN (SELECT conversation_no FROM nx_support_acceptance_sandbox_conversation WHERE run_id=#{runId})
                 OR EXISTS (SELECT 1 FROM nx_support_acceptance_sandbox_idempotency s
                              WHERE s.run_id=#{runId} AND (
                                   a.resource_id=s.business_key OR a.biz_no=s.business_key
                                OR a.resource_id=s.result_id OR a.biz_no=s.result_id
                                OR JSON_UNQUOTE(JSON_EXTRACT(a.detail_json,'$.idempotencyKey'))=s.command_key
                              ))
               )
            """)
    int productionAuditDelta(@Param("runId") String runId,@Param("fromAt") LocalDateTime fromAt,@Param("toAt") LocalDateTime toAt);
    @Select("""
            SELECT COUNT(*) FROM nx_admin_idempotency_record i
             WHERE (i.created_at BETWEEN #{fromAt} AND #{toAt} OR i.updated_at BETWEEN #{fromAt} AND #{toAt})
               AND EXISTS (SELECT 1 FROM nx_support_acceptance_sandbox_idempotency s
                              JOIN nx_support_acceptance_sandbox_run r ON r.run_id=s.run_id AND r.account_id=s.account_id
                              JOIN nx_user u ON u.id=r.account_id AND u.sandbox=1 AND u.is_deleted=0
                             WHERE s.run_id=#{runId} AND i.idempotency_key=s.command_key
                               AND (i.scope LIKE CONCAT('APP_SUPPORT_%:', r.account_id)
                                 OR i.scope LIKE CONCAT('APP_CONVERSATION_%:', r.account_id)
                                 OR i.scope LIKE 'M3_CONVERSATION_%'))
            """)
    int productionIdempotencyDelta(@Param("runId") String runId,@Param("fromAt") LocalDateTime fromAt,@Param("toAt") LocalDateTime toAt);
    @Select("""
            SELECT COUNT(*) FROM nx_event_outbox o
             WHERE (o.created_at BETWEEN #{fromAt} AND #{toAt} OR o.updated_at BETWEEN #{fromAt} AND #{toAt})
               AND (
                    o.aggregate_id IN (SELECT ticket_no FROM nx_support_acceptance_sandbox_ticket WHERE run_id=#{runId})
                 OR o.aggregate_id IN (SELECT conversation_no FROM nx_support_acceptance_sandbox_conversation WHERE run_id=#{runId})
                 OR EXISTS (SELECT 1 FROM nx_support_acceptance_sandbox_idempotency s
                              WHERE s.run_id=#{runId} AND (
                                   o.aggregate_id=s.business_key OR o.aggregate_id=s.result_id
                                OR CAST(o.payload AS CHAR) LIKE CONCAT('%',#{runId},'%')
                                OR CAST(o.payload AS CHAR) LIKE CONCAT('%',s.command_key,'%')
                                OR CAST(o.payload AS CHAR) LIKE CONCAT('%',s.business_key,'%')
                                OR CAST(o.payload AS CHAR) LIKE CONCAT('%',s.result_id,'%')
                              ))
               )
            """)
    int productionOutboxDelta(@Param("runId") String runId,@Param("fromAt") LocalDateTime fromAt,@Param("toAt") LocalDateTime toAt);
}
